package clipbridge.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One ClipBridge computer: it watches the local clipboard, accepts and makes encrypted TCP
 * connections to other computers, finds them on the network, and reconnects when a link drops.
 *
 * Wire format (every message is a "frame": 1 byte type, 4 byte length, data):
 *   HELLO   plain   magic, node id, nonce, has-code flag, listening port, name
 *   SEALED  AES-GCM proof messages, clipboard text and heartbeats
 *   REFUSE  plain   a short reason, sent just before closing
 */
public final class Node implements Closeable {

    public interface Listener {
        /** The list of computers or their states changed. */
        default void onPeersChanged() {
        }

        /** New text reached the history. {@code fromRemote} is true when another computer sent it. */
        default void onClipboard(String source, String text, boolean fromRemote) {
        }

        /** Something the user should be told about (a refusal, a too-large copy, a lost link). */
        default void onProblem(String message) {
        }
    }

    public enum State {CONNECTED, CONNECTING, WAITING, REFUSED}

    public static final class PeerStatus {
        public final String name;
        public final String host;
        public final int port;
        public final State state;
        public final String detail;

        PeerStatus(String name, String host, int port, State state, String detail) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.state = state;
            this.detail = detail;
        }
    }

    public static final class Options {
        public String name = "My-PC";
        public int port = 5005;
        public String pairingCode = "";
        public ClipboardBackend backend;
        public History history = History.inMemory();
        public boolean discovery = true;
        public long pollMs = 400;
        public long retryMs = 5000;
        public long quickRetryMs = 1500;
        public long heartbeatMs = 10_000;
        public long readTimeoutMs = 30_000;
    }

    public static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_FRAME = MAX_TEXT_BYTES + 4096;
    private static final int HANDSHAKE_TIMEOUT_MS = 6000;
    private static final byte T_HELLO = 1, T_SEALED = 2, T_REFUSE = 3;
    private static final byte K_PROOF = 'P', K_TEXT = 'T', K_BEAT = 'H';
    private static final byte[] MAGIC = {'C', 'B', 'R', '1'};
    private static final Logger LOG = Logger.getLogger("clipbridge");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Options opt;
    private final ClipboardBackend backend;
    private final History history;
    private final byte[] myIdBytes = new byte[16];
    private final String myId;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile String name;
    private volatile String code = "";
    private volatile byte[] baseKey;
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile String discoveryError;

    private ServerSocket server;
    private Discovery discovery;
    private final Object clipLock = new Object();
    private String lastText = "";

    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    private final Map<String, Connection> conns = new ConcurrentHashMap<>();
    private final Object connLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger linksEstablished = new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(daemon("clipbridge-worker"));
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, daemon("clipbridge-timer"));

    // ---------------------------------------------------------------- setup

    public Node(Options options) {
        this.opt = options;
        if (options.backend == null) {
            throw new IllegalArgumentException("A clipboard backend is required");
        }
        this.backend = options.backend;
        this.history = options.history;
        this.name = cleanName(options.name);
        RANDOM.nextBytes(myIdBytes);
        this.myId = hex(myIdBytes);
        if (options.pairingCode != null && !options.pairingCode.trim().isEmpty()) {
            setPairingCode(options.pairingCode);
        }
    }

    /** Opens the listening port and starts all background work. */
    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(opt.port));
        } catch (BindException e) {
            server.close();
            throw new IOException("Port " + opt.port + " is already in use. Another copy of ClipBridge may be running; "
                    + "close it or choose another port.", e);
        }
        synchronized (clipLock) {
            String now = null;
            try {
                now = backend.get();
            } catch (RuntimeException ignored) {
                // start with an empty memory
            }
            lastText = now == null ? "" : now; // do not send what is already on the clipboard
        }
        pool.execute(this::acceptLoop);
        timer.scheduleWithFixedDelay(this::guarded_poll, opt.pollMs, opt.pollMs, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::dialLoop, 300, 500, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::heartbeat, opt.heartbeatMs, opt.heartbeatMs, TimeUnit.MILLISECONDS);
        if (opt.discovery) {
            try {
                discovery = new Discovery(myId, name, getPort(), this::discovered);
                discovery.start();
            } catch (IOException | RuntimeException e) {
                discoveryError = e.getMessage();
                discovery = null;
                LOG.log(Level.WARNING, "Automatic discovery is not available: " + e);
            }
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // ---------------------------------------------------------------- settings

    public int getPort() {
        return server == null ? opt.port : server.getLocalPort();
    }

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        this.name = cleanName(newName);
        if (discovery != null) {
            discovery.setName(this.name);
        }
    }

    public boolean hasPairingCode() {
        return baseKey != null;
    }

    public String getPairingCode() {
        return code;
    }

    /** Empty text removes the code. Throws IllegalArgumentException with a plain message for a bad code. */
    public void setPairingCode(String newCode) {
        String n = PairingCode.normalize(newCode);
        if (n.isEmpty()) {
            code = "";
            baseKey = null;
        } else {
            String problem = PairingCode.explainProblem(n);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
            baseKey = PairingCode.deriveKey(n);
            code = n;
        }
        // A new code means every old link is invalid and every refusal should be retried now.
        for (Connection c : conns.values()) {
            c.close();
        }
        for (Target t : targets.values()) {
            t.nextAttempt = 0;
            if (t.state == State.REFUSED) {
                t.state = State.WAITING;
                t.detail = "Retrying with the new pairing code";
            }
        }
        fireChanged();
    }

    public String getDiscoveryError() {
        return discoveryError;
    }

    public boolean isPaused() {
        return paused;
    }

    /** While paused nothing is sent and incoming text is ignored. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        fireChanged();
    }

    public History getHistory() {
        return history;
    }

    // ---------------------------------------------------------------- peers

    /**
     * Adds a computer by address (for networks where automatic discovery does not work).
     * Throws IllegalArgumentException with a plain message when the address is not usable.
     */
    public void connectTo(String host, int port) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            throw new IllegalArgumentException("Enter the other computer's IP address or name.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("The port must be a number from 1 to 65535.");
        }
        if (h.indexOf(' ') >= 0 || h.indexOf('/') >= 0) {
            throw new IllegalArgumentException("'" + h + "' is not a valid address.");
        }
        Target t = targets.computeIfAbsent(h + ":" + port, k -> new Target(h, port));
        t.manual = true;
        t.nextAttempt = 0;
        fireChanged();
    }

    public List<PeerStatus> peers() {
        List<PeerStatus> out = new ArrayList<>();
        for (Target t : targets.values()) {
            State s = t.state;
            String d = t.detail;
            if (t.peerId != null && conns.containsKey(t.peerId)) {
                s = State.CONNECTED;
                d = "Connected, encrypted";
            }
            out.add(new PeerStatus(t.name != null ? t.name : t.host, t.host, t.port, s, d));
        }
        out.sort(Comparator.comparing((PeerStatus p) -> p.name.toLowerCase(Locale.ROOT)).thenComparing(p -> p.host));
        return out;
    }

    public int connectedCount() {
        return conns.size();
    }

    /** How many links have been set up since start (a reconnect counts again). */
    public int linksEstablished() {
        return linksEstablished.get();
    }

    // ---------------------------------------------------------------- clipboard

    /** Puts the text on this computer's clipboard and sends it to every connected computer. */
    public void shareText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (clipLock) {
            backend.set(text);
            lastText = text;
        }
        publishLocal(text);
    }

    private void guarded_poll() {
        try {
            pollClipboard();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Clipboard poll failed: " + e);
        }
    }

    private void pollClipboard() {
        if (closed || paused) {
            return;
        }
        String now;
        synchronized (clipLock) {
            now = backend.get();
            if (now == null || now.isEmpty() || now.equals(lastText)) {
                return;
            }
            lastText = now;
        }
        publishLocal(now);
    }

    private void publishLocal(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES) {
            problem("That copy is too large to share (" + ((bytes + 1023) / 1024) + " KB). The limit is "
                    + (MAX_TEXT_BYTES / 1024) + " KB. It stays on this computer only.");
            return;
        }
        if (history.add("This computer", text)) {
            fireClipboard("This computer", text, false);
        }
        byte[] msg = message(K_TEXT, text.getBytes(StandardCharsets.UTF_8));
        for (Connection c : new ArrayList<>(conns.values())) {
            c.sendAsync(msg);
        }
    }

    private void receiveRemote(Connection from, String text) {
        if (paused) {
            return;
        }
        synchronized (clipLock) {
            try {
                backend.set(text);
            } catch (RuntimeException e) {
                problem("Could not put the text from " + from.peerName + " on the clipboard: " + e.getMessage());
                return;
            }
            lastText = text; // so our own poll does not send it straight back
        }
        if (history.add(from.peerName, text)) {
            fireClipboard(from.peerName, text, true);
        }
    }

    // ---------------------------------------------------------------- connecting

    private static final class Target {
        final String host;
        final int port;
        volatile String peerId;
        volatile String name;
        volatile State state = State.WAITING;
        volatile String detail = "Waiting to connect";
        volatile long nextAttempt;
        volatile long lastSeen = System.currentTimeMillis();
        volatile boolean manual;
        volatile String lastProblem = "";
        final AtomicBoolean busy = new AtomicBoolean();

        Target(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private void discovered(String host, int port, String id, String peerName) {
        if (closed || id.equals(myId)) {
            return;
        }
        Target t = byPeerId(id);
        if (t == null) {
            t = targets.get(host + ":" + port);
        }
        boolean isNew = false;
        if (t == null) {
            t = new Target(host, port);
            targets.put(host + ":" + port, t);
            isNew = true;
        }
        t.peerId = id;
        t.name = peerName;
        t.lastSeen = System.currentTimeMillis();
        if (isNew) {
            fireChanged();
        }
    }

    private Target byPeerId(String id) {
        for (Target t : targets.values()) {
            if (id.equals(t.peerId)) {
                return t;
            }
        }
        return null;
    }

    private void dialLoop() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Target> e : targets.entrySet()) {
            Target t = e.getValue();
            if (t.busy.get()) {
                continue;
            }
            boolean linked = t.peerId != null && conns.containsKey(t.peerId);
            if (!linked && !t.manual && now - t.lastSeen > 90_000) {
                targets.remove(e.getKey(), t); // a computer that stopped announcing itself
                fireChanged();
                continue;
            }
            if (linked || now < t.nextAttempt) {
                continue;
            }
            if (baseKey == null) {
                if (t.state != State.WAITING || !"Set a pairing code to connect".equals(t.detail)) {
                    t.state = State.WAITING;
                    t.detail = "Set a pairing code to connect";
                    fireChanged();
                }
                continue;
            }
            if (t.busy.compareAndSet(false, true)) {
                pool.execute(() -> dial(t));
            }
        }
    }

    private void dial(Target t) {
        Socket s = new Socket();
        try {
            t.state = State.CONNECTING;
            t.detail = "Connecting...";
            fireChanged();
            s.connect(new InetSocketAddress(t.host, t.port), 3000);
            Connection c = handshake(s, true, t);
            if (c != null) {
                t.lastProblem = "";
                c.readLoop(); // returns when the link is gone
                t.nextAttempt = System.currentTimeMillis() + opt.quickRetryMs;
                t.state = State.WAITING;
                t.detail = "Connection lost. Reconnecting...";
            } else {
                t.nextAttempt = System.currentTimeMillis() + opt.retryMs;
            }
        } catch (Refused r) {
            closeQuietly(s);
            t.state = State.REFUSED;
            t.detail = r.getMessage();
            t.nextAttempt = System.currentTimeMillis() + 20_000;
            reportOnce(t, r.getMessage());
        } catch (SelfConnection self) {
            closeQuietly(s);
            targets.remove(t.host + ":" + t.port, t); // that address is this very computer
        } catch (IOException e) {
            closeQuietly(s);
            t.state = State.WAITING;
            t.detail = "Not reachable. Trying again every " + (opt.retryMs / 1000) + " seconds.";
            t.nextAttempt = System.currentTimeMillis() + opt.retryMs;
        } finally {
            t.busy.set(false);
            fireChanged();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                pool.execute(() -> incoming(s));
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "Accept failed: " + e);
                    sleep(200);
                }
            }
        }
    }

    private void incoming(Socket s) {
        try {
            Connection c = handshake(s, false, null);
            if (c != null) {
                c.readLoop();
            }
        } catch (Refused r) {
            closeQuietly(s);
            Target t = r.target;
            if (t != null) {
                t.state = State.REFUSED;
                t.detail = r.getMessage();
                reportOnce(t, r.getMessage());
            } else {
                problem(r.getMessage());
            }
            fireChanged();
        } catch (SelfConnection | IOException ignored) {
            closeQuietly(s);
        }
    }

    private void reportOnce(Target t, String message) {
        if (!message.equals(t.lastProblem)) {
            t.lastProblem = message;
            problem(message);
        }
    }

    // ---------------------------------------------------------------- handshake

    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        transient Target target;

        Refused(String message) {
            super(message);
        }
    }

    private static final class SelfConnection extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class Hello {
        String id;
        byte[] nonce;
        boolean hasCode;
        int port;
        String name;
    }

    private static final class Frame {
        byte type;
        byte[] data;
    }

    /** Runs the handshake. Returns the new connection, or null when a better link already exists. */
    private Connection handshake(Socket s, boolean initiator, Target dialTarget)
            throws IOException, Refused, SelfConnection {
        s.setTcpNoDelay(true);
        s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

        byte[] myNonce = new byte[16];
        RANDOM.nextBytes(myNonce);
        byte[] myHello = buildHello(myNonce);

        Hello peer;
        if (initiator) {
            writeFrame(out, T_HELLO, myHello);
            peer = readHello(in);
        } else {
            peer = readHello(in);
            writeFrame(out, T_HELLO, myHello);
        }
        if (peer.id.equals(myId)) {
            throw new SelfConnection();
        }

        String remoteHost = ((InetSocketAddress) s.getRemoteSocketAddress()).getAddress().getHostAddress();
        Target target = resolveTarget(dialTarget, remoteHost, peer);
        try {
            return finishHandshake(s, in, out, initiator, myNonce, peer, target);
        } catch (Refused r) {
            r.target = target;
            throw r;
        }
    }

    private Connection finishHandshake(Socket s, DataInputStream in, DataOutputStream out, boolean initiator,
                                       byte[] myNonce, Hello peer, Target target) throws IOException, Refused {
        byte[] base = baseKey;
        if (base == null) {
            throw new Refused("This computer has no pairing code yet, so it cannot connect to " + peer.name + ".");
        }
        if (!peer.hasCode) {
            throw new Refused(peer.name + " has no pairing code yet. Enter the same code there.");
        }

        byte[] session;
        try {
            session = initiator
                    ? SecureChannel.deriveSession(base, myNonce, peer.nonce)
                    : SecureChannel.deriveSession(base, peer.nonce, myNonce);
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption is not available: " + e.getMessage(), e);
        }
        SecureChannel channel = new SecureChannel(session, initiator);
        String wrongCode = peer.name + " has a different pairing code.";
        try {
            if (initiator) {
                writeFrame(out, T_SEALED, channel.seal(proof((byte) 'I')));
                Frame f = readFrame(in);
                if (f.type == T_REFUSE) {
                    throw new Refused(wrongCode);
                }
                expectProof(channel, f, (byte) 'A', wrongCode);
            } else {
                Frame f = readFrame(in);
                if (f.type == T_REFUSE) {
                    throw new Refused(wrongCode);
                }
                try {
                    expectProof(channel, f, (byte) 'I', wrongCode);
                } catch (Refused r) {
                    try {
                        writeFrame(out, T_REFUSE, "different pairing code".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // the peer is gone; the refusal is still reported here
                    }
                    throw r;
                }
                writeFrame(out, T_SEALED, channel.seal(proof((byte) 'A')));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed: " + e.getMessage(), e);
        }

        s.setSoTimeout((int) opt.readTimeoutMs);
        Connection c = new Connection(s, in, out, channel, peer.id, peer.name, initiator, target);
        return register(c) ? c : null;
    }

    private static byte[] proof(byte who) {
        return new byte[]{K_PROOF, who};
    }

    private static void expectProof(SecureChannel ch, Frame f, byte who, String wrongCode) throws Refused, IOException {
        if (f.type != T_SEALED) {
            throw new IOException("Unexpected message during the handshake");
        }
        byte[] plain;
        try {
            plain = ch.open(f.data);
        } catch (GeneralSecurityException e) {
            throw new Refused(wrongCode);
        }
        if (plain.length != 2 || plain[0] != K_PROOF || plain[1] != who) {
            throw new Refused(wrongCode);
        }
    }

    private Target resolveTarget(Target dialTarget, String remoteHost, Hello peer) {
        synchronized (targets) {
            Target existing = byPeerId(peer.id);
            Target t = dialTarget;
            if (t == null) {
                t = existing != null ? existing : targets.get(remoteHost + ":" + peer.port);
                if (t == null) {
                    t = new Target(remoteHost, peer.port);
                    targets.put(remoteHost + ":" + peer.port, t);
                }
            } else if (existing != null && existing != t) {
                targets.remove(t.host + ":" + t.port, t); // two addresses for the same computer: keep one
                t = existing;
            }
            t.peerId = peer.id;
            t.name = peer.name;
            t.lastSeen = System.currentTimeMillis();
            return t;
        }
    }

    private boolean register(Connection c) {
        synchronized (connLock) {
            Connection old = conns.get(c.peerId);
            if (old != null) {
                boolean iAmSmaller = myId.compareTo(c.peerId) < 0;
                // Both computers apply the same rule, so they keep the same single link:
                // the one started by the computer with the smaller id.
                boolean newPreferred = iAmSmaller == c.initiator;
                if (c.initiator == old.initiator || newPreferred) {
                    old.close();
                } else {
                    return false;
                }
            }
            conns.put(c.peerId, c);
        }
        linksEstablished.incrementAndGet();
        c.target.state = State.CONNECTED;
        c.target.detail = "Connected, encrypted";
        fireChanged();
        return true;
    }

    private byte[] buildHello(byte[] nonce) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.write(MAGIC);
        d.write(myIdBytes);
        d.write(nonce);
        d.writeBoolean(baseKey != null);
        d.writeShort(getPort());
        d.writeUTF(name);
        return bo.toByteArray();
    }

    private static Hello readHello(DataInputStream in) throws IOException, Refused {
        Frame f = readFrame(in);
        if (f.type == T_REFUSE) {
            throw new Refused(new String(f.data, StandardCharsets.UTF_8));
        }
        if (f.type != T_HELLO || f.data.length > 512) {
            throw new IOException("Not a ClipBridge computer");
        }
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(f.data));
        byte[] magic = new byte[4];
        d.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Not a ClipBridge computer");
        }
        Hello h = new Hello();
        byte[] id = new byte[16];
        d.readFully(id);
        h.id = hex(id);
        h.nonce = new byte[16];
        d.readFully(h.nonce);
        h.hasCode = d.readBoolean();
        h.port = d.readUnsignedShort();
        h.name = cleanName(d.readUTF());
        return h;
    }

    // ---------------------------------------------------------------- one live link

    private final class Connection {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final SecureChannel channel;
        final String peerId;
        final String peerName;
        final boolean initiator;
        final Target target;
        private volatile boolean dead;
        private final ExecutorService sender = Executors.newSingleThreadExecutor(daemon("clipbridge-send"));

        Connection(Socket socket, DataInputStream in, DataOutputStream out, SecureChannel channel,
                   String peerId, String peerName, boolean initiator, Target target) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.channel = channel;
            this.peerId = peerId;
            this.peerName = peerName;
            this.initiator = initiator;
            this.target = target;
        }

        /** Sealing and writing happen in one place and in order, so the message counters stay in step. */
        void sendAsync(byte[] plainMessage) {
            try {
                sender.execute(() -> {
                    try {
                        synchronized (out) {
                            writeFrame(out, T_SEALED, channel.seal(plainMessage));
                        }
                    } catch (IOException | GeneralSecurityException e) {
                        close();
                    }
                });
            } catch (RejectedExecutionException closedAlready) {
                // the link is gone
            }
        }

        void readLoop() {
            String why = "The connection to " + peerName + " was closed.";
            try {
                while (!dead && !closed) {
                    Frame f = readFrame(in);
                    if (f.type != T_SEALED) {
                        throw new IOException("Unexpected message");
                    }
                    byte[] plain = channel.open(f.data);
                    if (plain.length == 0) {
                        continue;
                    }
                    if (plain[0] == K_TEXT) {
                        receiveRemote(this, new String(plain, 1, plain.length - 1, StandardCharsets.UTF_8));
                    } // K_BEAT and anything unknown: nothing to do
                }
            } catch (SocketTimeoutException e) {
                why = "No sign of life from " + peerName + " for " + (opt.readTimeoutMs / 1000) + " seconds.";
            } catch (GeneralSecurityException e) {
                why = "A message from " + peerName + " failed its security check, so the connection was closed.";
                if (!dead) {
                    problem(why);
                }
            } catch (IOException e) {
                // normal end of the link
            } finally {
                close();
                synchronized (connLock) {
                    conns.remove(peerId, this);
                }
                if (!closed) {
                    LOG.fine(why);
                }
                fireChanged();
            }
        }

        void close() {
            dead = true;
            closeQuietly(socket);
            sender.shutdownNow();
        }
    }

    private void heartbeat() {
        byte[] beat = message(K_BEAT, new byte[0]);
        for (Connection c : new ArrayList<>(conns.values())) {
            c.sendAsync(beat);
        }
    }

    // ---------------------------------------------------------------- frames

    private static void writeFrame(DataOutputStream out, byte type, byte[] data) throws IOException {
        out.writeByte(type);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        Frame f = new Frame();
        f.type = in.readByte();
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("The other side sent an invalid message length");
        }
        f.data = new byte[len];
        in.readFully(f.data);
        return f;
    }

    private static byte[] message(byte kind, byte[] payload) {
        byte[] m = new byte[payload.length + 1];
        m[0] = kind;
        System.arraycopy(payload, 0, m, 1, payload.length);
        return m;
    }

    // ---------------------------------------------------------------- helpers

    private void fireChanged() {
        if (closed) {
            return;
        }
        for (Listener l : listeners) {
            try {
                l.onPeersChanged();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed: " + e);
            }
        }
    }

    private void fireClipboard(String source, String text, boolean remote) {
        for (Listener l : listeners) {
            try {
                l.onClipboard(source, text, remote);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed: " + e);
            }
        }
    }

    private void problem(String message) {
        LOG.info(message);
        for (Listener l : listeners) {
            try {
                l.onProblem(message);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed: " + e);
            }
        }
    }

    private static String cleanName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\p{Cntrl}|]", "").trim();
        if (s.isEmpty()) {
            s = "My-PC";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemon(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }

    /** Cuts every live link (used by tests and the "Reconnect" button). They come back by themselves. */
    public void dropConnections() {
        for (Connection c : new ArrayList<>(conns.values())) {
            c.close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (discovery != null) {
            discovery.close();
        }
        if (server != null) {
            closeQuietly(server);
        }
        for (Connection c : new ArrayList<>(conns.values())) {
            c.close();
        }
        timer.shutdownNow();
        pool.shutdownNow();
        history.flush();
    }
}
