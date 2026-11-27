package clipbridge;

import clipbridge.core.*;

import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All automated tests in one file, with no libraries needed:
 *     java -cp out clipbridge.AllTests
 * The exit code is 0 when everything passes.
 */
public final class AllTests {
    private static int passed;
    private static int failed;
    private static int skipped;
    private static final List<String> failures = new ArrayList<>();
    private static Path tmp;

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body body) {
        long t0 = System.currentTimeMillis();
        try {
            body.run();
            passed++;
            System.out.printf("  ok    %-62s %5d ms%n", name, System.currentTimeMillis() - t0);
        } catch (SkipTest s) {
            skipped++;
            System.out.printf("  skip  %-62s %s%n", name, s.getMessage());
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.printf("  FAIL  %s%n          %s%n", name, t);
            t.printStackTrace(System.out);
        }
    }

    static final class SkipTest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SkipTest(String why) {
            super(why);
        }
    }

    static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static boolean waitFor(BooleanSupplier cond, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return cond.getAsBoolean();
    }

    static void mustThrow(Class<? extends Throwable> type, Body body, String what) throws Exception {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    // ------------------------------------------------------------------ test nodes

    static final class TestNode implements AutoCloseable {
        final Node node;
        final FileClipboardBackend clip;
        final List<String> problems = Collections.synchronizedList(new ArrayList<>());
        final List<String> received = Collections.synchronizedList(new ArrayList<>());

        TestNode(String name, String code) throws IOException {
            this(name, code, 0);
        }

        TestNode(String name, String code, int port) throws IOException {
            clip = new FileClipboardBackend(tmp.resolve(name + "-" + System.nanoTime() + ".txt"));
            Node.Options o = new Node.Options();
            o.name = name;
            o.port = port;
            o.pairingCode = code;
            o.backend = clip;
            o.discovery = false;
            o.pollMs = 50;
            o.retryMs = 400;
            o.quickRetryMs = 150;
            o.heartbeatMs = 500;
            o.readTimeoutMs = 3000;
            node = new Node(o);
            node.addListener(new Node.Listener() {
                @Override
                public void onProblem(String m) {
                    problems.add(m);
                }

                @Override
                public void onClipboard(String source, String text, boolean fromRemote) {
                    if (fromRemote) {
                        received.add(text);
                    }
                }
            });
            node.start();
        }

        void copy(String text) {
            clip.set(text); // as if the user pressed Ctrl+C
        }

        String clipboard() {
            return clip.get();
        }

        void linkTo(TestNode other) {
            node.connectTo("127.0.0.1", other.node.getPort());
        }

        @Override
        public void close() {
            node.close();
        }
    }

    static boolean connected(TestNode a, TestNode b) {
        return a.node.connectedCount() == 1 && b.node.connectedCount() == 1;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Logger.getLogger("clipbridge").setLevel(Level.OFF);
        tmp = Files.createTempDirectory("clipbridge-test");
        System.out.println("ClipBridge tests");

        System.out.println("Pairing code");
        pairingCodeTests();
        System.out.println("Encryption channel");
        channelTests();
        System.out.println("History");
        historyTests();
        System.out.println("Settings");
        configTests();
        System.out.println("File clipboard");
        fileClipboardTests();
        System.out.println("Two computers");
        networkTests();

        deleteTree(tmp);
        System.out.printf("%nRan %d tests: %d passed, %d failed, %d skipped%n", passed + failed + skipped, passed, failed, skipped);
        if (failed > 0) {
            System.out.println("FAILED");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("OK");
        System.exit(0); // background threads are daemons, but be explicit
    }

    // ------------------------------------------------------------------ pairing code

    static void pairingCodeTests() {
        test("generated codes are valid, 16 characters and different each time", () -> {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                String c = PairingCode.generate();
                eq(16, c.length(), "length");
                check(PairingCode.isValid(c), "valid: " + c);
                seen.add(c);
            }
            eq(200, seen.size(), "all different");
        });
        test("generated codes never contain 0, 1, I or O", () -> {
            for (int i = 0; i < 500; i++) {
                String c = PairingCode.generate();
                check(c.matches("[A-HJ-NP-Z2-9]{16}"), "bad characters in " + c);
            }
        });
        test("typing is forgiving: lower case, spaces and dashes are accepted", () -> {
            String c = PairingCode.generate();
            String typed = PairingCode.format(c).toLowerCase().replace('-', ' ');
            eq(c, PairingCode.normalize(typed), "normalized");
            check(PairingCode.isValid(typed), "valid when typed loosely");
        });
        test("format groups the code as XXXX-XXXX-XXXX-XXXX", () -> {
            eq("ABCD-EFGH-JKLM-NPQR", PairingCode.format("abcdefghjklmnpqr"), "format");
        });
        test("bad codes are explained in plain words", () -> {
            check(PairingCode.explainProblem("").contains("Enter a pairing code"), "empty");
            check(PairingCode.explainProblem("ABC").contains("16 characters"), "short");
            check(PairingCode.explainProblem("ABCDEFGH0JKLMNPQ").contains("'0'"), "zero");
            check(PairingCode.explainProblem(null) != null, "null");
            eq(null, PairingCode.explainProblem("ABCD-EFGH-JKLM-NPQR"), "good code");
        });
        test("the same code always gives the same key, different codes different keys", () -> {
            byte[] a1 = PairingCode.deriveKey("ABCD-EFGH-JKLM-NPQR");
            byte[] a2 = PairingCode.deriveKey("abcdefghjklmnpqr");
            byte[] b = PairingCode.deriveKey("ABCDEFGHJKLMNPQS");
            eq(32, a1.length, "AES-256 key size");
            check(Arrays.equals(a1, a2), "same code");
            check(!Arrays.equals(a1, b), "different code");
        });
    }

    // ------------------------------------------------------------------ channel

    static SecureChannel[] pair(byte[] base) throws GeneralSecurityException {
        byte[] n1 = new byte[16];
        byte[] n2 = new byte[16];
        new Random(1).nextBytes(n1);
        new Random(2).nextBytes(n2);
        byte[] session = SecureChannel.deriveSession(base, n1, n2);
        return new SecureChannel[]{new SecureChannel(session, true), new SecureChannel(session, false)};
    }

    static void channelTests() {
        byte[] base = PairingCode.deriveKey(PairingCode.generate());
        test("a message sent by one side opens on the other, both directions", () -> {
            SecureChannel[] c = pair(base);
            eq("hello", new String(c[1].open(c[0].seal("hello".getBytes()))), "init to acceptor");
            eq("world", new String(c[0].open(c[1].seal("world".getBytes()))), "acceptor to init");
        });
        test("the clipboard text is not visible in the encrypted bytes", () -> {
            SecureChannel[] c = pair(base);
            byte[] sealed = c[0].seal("my secret password 12345".getBytes());
            check(!new String(sealed, "ISO-8859-1").contains("secret"), "plain text leaked");
        });
        test("the same text sealed twice gives different bytes", () -> {
            SecureChannel[] c = pair(base);
            byte[] one = c[0].seal("same".getBytes());
            byte[] two = c[0].seal("same".getBytes());
            check(!Arrays.equals(one, two), "identical ciphertext would reveal repeats");
        });
        test("a replayed message is rejected", () -> {
            SecureChannel[] c = pair(base);
            byte[] m = c[0].seal("pay 100".getBytes());
            c[1].open(m);
            mustThrow(GeneralSecurityException.class, () -> c[1].open(m), "replay");
        });
        test("messages out of order are rejected", () -> {
            SecureChannel[] c = pair(base);
            c[0].seal("first".getBytes());
            byte[] second = c[0].seal("second".getBytes());
            mustThrow(GeneralSecurityException.class, () -> c[1].open(second), "skipped message");
        });
        test("a changed message is rejected", () -> {
            SecureChannel[] c = pair(base);
            byte[] m = c[0].seal("amount 100".getBytes());
            m[0] ^= 1;
            mustThrow(GeneralSecurityException.class, () -> c[1].open(m), "tampering");
        });
        test("a message reflected back to its sender is rejected", () -> {
            SecureChannel[] c = pair(base);
            byte[] m = c[0].seal("echo".getBytes());
            mustThrow(GeneralSecurityException.class, () -> c[0].open(m), "reflection");
        });
        test("the wrong pairing code cannot open a message", () -> {
            SecureChannel[] right = pair(base);
            SecureChannel[] wrong = pair(PairingCode.deriveKey(PairingCode.generate()));
            byte[] m = right[0].seal("secret".getBytes());
            mustThrow(GeneralSecurityException.class, () -> wrong[1].open(m), "wrong key");
        });
        test("a failed open does not break the channel for the real next message", () -> {
            SecureChannel[] c = pair(base);
            byte[] good = c[0].seal("one".getBytes());
            byte[] bad = good.clone();
            bad[2] ^= 4;
            mustThrow(GeneralSecurityException.class, () -> c[1].open(bad), "bad copy");
            eq("one", new String(c[1].open(good)), "real message still opens");
        });
        test("every connection gets its own session key", () -> {
            byte[] n1 = new byte[16], n2 = new byte[16], n3 = new byte[16];
            n1[0] = 1;
            n2[0] = 2;
            n3[0] = 3;
            check(!Arrays.equals(SecureChannel.deriveSession(base, n1, n2), SecureChannel.deriveSession(base, n1, n3)),
                    "different nonces must give different keys");
        });
    }

    // ------------------------------------------------------------------ history

    static void historyTests() {
        test("newest first and an identical repeat is not added twice", () -> {
            History h = History.inMemory();
            check(h.add("This computer", "one"), "first");
            check(h.add("PC-B", "two"), "second");
            check(!h.add("PC-B", "two"), "repeat");
            eq("two", h.snapshot().get(0).getText(), "newest");
            eq(2, h.size(), "size");
        });
        test("empty text is ignored", () -> {
            History h = History.inMemory();
            check(!h.add("x", ""), "empty");
            check(!h.add("x", null), "null");
        });
        test("only the newest 100 are kept", () -> {
            History h = History.inMemory();
            for (int i = 0; i < 130; i++) {
                h.add("x", "item " + i);
            }
            eq(100, h.size(), "size");
            eq("item 129", h.snapshot().get(0).getText(), "newest kept");
            eq("item 30", h.snapshot().get(99).getText(), "oldest kept");
        });
        test("search finds text and computer names, ignoring case", () -> {
            History h = History.inMemory();
            h.add("OFFICE-PC", "Meeting at 5");
            h.add("This computer", "grocery list");
            eq(1, h.search("MEETING").size(), "text");
            eq(1, h.search("office").size(), "source");
            eq(2, h.search("  ").size(), "blank gives everything");
            eq(0, h.search("zzz").size(), "no match");
        });
        test("remove and clear", () -> {
            History h = History.inMemory();
            h.add("a", "one");
            h.add("a", "two");
            h.remove(h.snapshot().get(0));
            eq(1, h.size(), "after remove");
            h.clear();
            eq(0, h.size(), "after clear");
        });
        test("history is saved to disk and loaded again", () -> {
            Path f = tmp.resolve("hist-save").resolve("history.bin");
            History h = History.load(f);
            h.add("PC-B", "saved text अआ ✓");
            h.add("This computer", "second");
            h.flush();
            History again = History.load(f);
            eq(2, again.size(), "loaded size");
            eq("second", again.snapshot().get(0).getText(), "order");
            eq("saved text अआ ✓", again.snapshot().get(1).getText(), "unicode text");
            eq("PC-B", again.snapshot().get(1).getSource(), "source");
        });
        test("a damaged history file gives an empty history instead of a crash", () -> {
            Path f = tmp.resolve("hist-bad.bin");
            Files.write(f, "this is not a serialized object".getBytes());
            eq(0, History.load(f).size(), "empty");
        });
        test("a history file holding some other object type is not trusted", () -> {
            Path f = tmp.resolve("hist-evil.bin");
            try (java.io.ObjectOutputStream o = new java.io.ObjectOutputStream(Files.newOutputStream(f))) {
                o.writeObject(new java.util.Date());
            }
            eq(0, History.load(f).size(), "empty");
        });
        test("preview shortens long and multi-line text", () -> {
            HistoryEntry e = new HistoryEntry(0, "x", "line one\n\n   line   two " + "y".repeat(100));
            String p = e.preview(20);
            eq(20, p.length(), "length");
            check(!p.contains("\n"), "single line");
        });
    }

    // ------------------------------------------------------------------ config

    static void configTests() {
        test("defaults when there is no settings file", () -> {
            Config c = Config.load(tmp.resolve("cfg-none"));
            eq(5005, c.getPort(), "port");
            eq("", c.getPairingCode(), "code");
            check(!c.getName().isEmpty(), "a default computer name exists");
        });
        test("settings are saved and loaded again", () -> {
            Path dir = tmp.resolve("cfg-save");
            Config c = Config.load(dir);
            String code = PairingCode.generate();
            c.setName("Shaurya's laptop");
            c.setPort(6000);
            c.setPairingCode(PairingCode.format(code));
            c.save();
            Config again = Config.load(dir);
            eq("Shaurya's laptop", again.getName(), "name");
            eq(6000, again.getPort(), "port");
            eq(code, again.getPairingCode(), "code");
        });
        test("a bad port or a bad code in the file falls back to safe values", () -> {
            Path dir = tmp.resolve("cfg-bad");
            Files.createDirectories(dir);
            Files.write(dir.resolve("config.properties"), "port=banana\npairingCode=short\n".getBytes());
            Config c = Config.load(dir);
            eq(5005, c.getPort(), "port");
            eq("", c.getPairingCode(), "code");
        });
        test("a port outside 1-65535 falls back to the default", () -> {
            Path dir = tmp.resolve("cfg-range");
            Files.createDirectories(dir);
            Files.write(dir.resolve("config.properties"), "port=99999\n".getBytes());
            eq(5005, Config.load(dir).getPort(), "port");
        });
        test("a damaged settings file does not crash", () -> {
            Path dir = tmp.resolve("cfg-garbage");
            Files.createDirectories(dir);
            Files.write(dir.resolve("config.properties"), new byte[]{'\\', 'u', 'Z', 'Z', 'Z', 'Z', '=', 1});
            check(Config.load(dir).getPort() > 0, "still usable");
        });
    }

    // ------------------------------------------------------------------ file clipboard

    static void fileClipboardTests() {
        test("an empty clipboard reads as null, then returns what was set", () -> {
            FileClipboardBackend b = new FileClipboardBackend(tmp.resolve("fc1.txt"));
            eq(null, b.get(), "empty");
            b.set("hello नमस्ते");
            eq("hello नमस्ते", b.get(), "value");
        });
        test("line endings and trailing spaces are kept exactly", () -> {
            FileClipboardBackend b = new FileClipboardBackend(tmp.resolve("fc2.txt"));
            b.set("a\r\nb\nc  ");
            eq("a\r\nb\nc  ", b.get(), "exact");
        });
        test("one thread writing and another reading never fails (Windows file-lock case)", () -> {
            FileClipboardBackend b = new FileClipboardBackend(tmp.resolve("fc3.txt"));
            b.set("start");
            AtomicInteger errors = new AtomicInteger();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 1500; i++) {
                        b.set("value " + i);
                    }
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            });
            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < 1500; i++) {
                        String v = b.get();
                        if (v == null || !v.startsWith("value ") && !v.equals("start")) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            });
            writer.start();
            reader.start();
            writer.join();
            reader.join();
            eq(0, errors.get(), "errors");
        });
    }

    // ------------------------------------------------------------------ network

    static void networkTests() {
        final String code = PairingCode.generate();

        test("two computers with the same code connect and say so", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "both sides connected");
                eq(Node.State.CONNECTED, a.node.peers().get(0).state, "A sees B connected");
                eq("B", a.node.peers().get(0).name, "A knows B's name");
                eq("A", b.node.peers().get(0).name, "B knows A's name");
            }
        });
        test("text copied on A appears on B, and the other way round", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                a.copy("hello from A");
                check(waitFor(() -> "hello from A".equals(b.clipboard()), 4000), "A to B");
                b.copy("reply from B ✓ नमस्ते");
                check(waitFor(() -> "reply from B ✓ नमस्ते".equals(a.clipboard()), 4000), "B to A");
            }
        });
        test("history on the receiving side records which computer sent it", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                a.copy("from A");
                check(waitFor(() -> b.node.getHistory().size() == 1, 4000), "arrived");
                eq("A", b.node.getHistory().snapshot().get(0).getSource(), "source");
                eq("This computer", a.node.getHistory().snapshot().get(0).getSource(), "sender's own entry");
            }
        });
        test("text is not echoed back and forth (no ping-pong)", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                a.copy("once only");
                check(waitFor(() -> "once only".equals(b.clipboard()), 4000), "arrived");
                Thread.sleep(800);
                eq(1, a.node.getHistory().size(), "A history");
                eq(1, b.node.getHistory().size(), "B history");
                eq(1, b.received.size(), "B received once");
                eq(0, a.received.size(), "A received nothing");
            }
        });
        test("many quick copies all arrive, the last one wins", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                for (int i = 0; i < 12; i++) {
                    a.node.shareText("rapid " + i);
                }
                check(waitFor(() -> "rapid 11".equals(b.clipboard()), 5000), "last arrives");
                check(waitFor(() -> b.node.getHistory().size() == 12, 5000), "all 12 in B's history, got " + b.node.getHistory().size());
            }
        });
        test("text already on the clipboard at startup is not sent", () -> {
            Path f = tmp.resolve("preexisting.txt");
            Files.write(f, "old text".getBytes());
            Node.Options o = new Node.Options();
            o.name = "A";
            o.port = 0;
            o.pairingCode = code;
            o.backend = new FileClipboardBackend(f);
            o.discovery = false;
            o.pollMs = 50;
            try (Node n = new Node(o); TestNode b = new TestNode("B", code)) {
                n.start();
                n.connectTo("127.0.0.1", b.node.getPort());
                check(waitFor(() -> n.connectedCount() == 1, 5000), "connected");
                Thread.sleep(500);
                eq(0, b.node.getHistory().size(), "nothing sent");
            }
        });
        test("a computer with a different code is refused, and both sides are told why", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", PairingCode.generate())) {
                a.linkTo(b);
                check(waitFor(() -> !a.problems.isEmpty() && !b.problems.isEmpty(), 6000), "both told");
                check(a.problems.get(0).contains("B has a different pairing code"), "A says: " + a.problems);
                check(b.problems.get(0).contains("A has a different pairing code"), "B says: " + b.problems);
                eq(0, a.node.connectedCount(), "A not connected");
                eq(0, b.node.connectedCount(), "B not connected");
                eq(Node.State.REFUSED, a.node.peers().get(0).state, "A shows refused");
            }
        });
        test("nothing is shared with a computer that has the wrong code", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", PairingCode.generate())) {
                a.linkTo(b);
                waitFor(() -> !a.problems.isEmpty(), 6000);
                a.copy("private text");
                Thread.sleep(700);
                eq(0, b.node.getHistory().size(), "B's history");
                eq(null, b.clipboard(), "B's clipboard");
            }
        });
        test("a computer with no code is refused and told to set one", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", "")) {
                a.linkTo(b);
                check(waitFor(() -> !a.problems.isEmpty(), 6000), "A told");
                check(a.problems.get(0).contains("B has no pairing code"), "A says: " + a.problems);
                eq(0, a.node.connectedCount(), "not connected");
            }
        });
        test("this computer without a code does not connect and says what to do", () -> {
            try (TestNode a = new TestNode("A", ""); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> a.node.peers().get(0).detail.contains("Set a pairing code"), 3000), "hint shown");
                eq(0, a.node.connectedCount(), "not connected");
            }
        });
        test("entering the right code later connects by itself", () -> {
            try (TestNode a = new TestNode("A", PairingCode.generate()); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> !a.problems.isEmpty(), 6000), "refused first");
                a.node.setPairingCode(PairingCode.format(code));
                check(waitFor(() -> connected(a, b), 6000), "connected after fixing the code");
                a.copy("now works");
                check(waitFor(() -> "now works".equals(b.clipboard()), 4000), "text flows");
            }
        });
        test("changing the code on one side disconnects until the other side matches", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                String fresh = PairingCode.generate();
                a.node.setPairingCode(fresh);
                check(waitFor(() -> a.node.connectedCount() == 0 && b.node.connectedCount() == 0, 4000), "dropped");
                b.node.setPairingCode(fresh);
                check(waitFor(() -> connected(a, b), 8000), "back together");
            }
        });
        test("a cut connection comes back by itself", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                int before = a.node.linksEstablished();
                a.node.dropConnections();
                check(waitFor(() -> a.node.linksEstablished() > before && connected(a, b), 8000), "reconnected by itself");
                a.copy("after reconnect");
                check(waitFor(() -> "after reconnect".equals(b.clipboard()), 4000), "text flows again");
            }
        });
        test("the other computer starting later is found and connected", () -> {
            int port;
            try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            try (TestNode a = new TestNode("A", code)) {
                a.node.connectTo("127.0.0.1", port);
                Thread.sleep(600);
                eq(0, a.node.connectedCount(), "B not started yet");
                try (TestNode b = new TestNode("B", code, port)) {
                    check(waitFor(() -> connected(a, b), 8000), "connected once B is up");
                }
            }
        });
        test("when the other computer closes, this side notices and keeps running", () -> {
            try (TestNode a = new TestNode("A", code)) {
                TestNode b = new TestNode("B", code);
                a.linkTo(b);
                check(waitFor(() -> a.node.connectedCount() == 1, 5000), "connected");
                b.close();
                check(waitFor(() -> a.node.connectedCount() == 0, 4000), "noticed");
                a.copy("still alive");
                eq("still alive", a.clipboard(), "local clipboard unaffected");
            }
        });
        test("both computers dialling each other at once end up with one working link", () -> {
            for (int round = 0; round < 5; round++) {
                try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                    a.linkTo(b);
                    b.linkTo(a);
                    check(waitFor(() -> connected(a, b), 6000), "round " + round + ": connected");
                    Thread.sleep(500);
                    eq(1, a.node.connectedCount(), "round " + round + ": A links");
                    eq(1, b.node.connectedCount(), "round " + round + ": B links");
                    final String text = "round " + round;
                    a.copy(text);
                    check(waitFor(() -> text.equals(b.clipboard()), 4000), "text flows");
                }
            }
        });
        test("pointing a computer at itself is ignored, not an endless loop", () -> {
            try (TestNode a = new TestNode("A", code)) {
                a.node.connectTo("127.0.0.1", a.node.getPort());
                check(waitFor(() -> a.node.peers().isEmpty(), 4000), "self entry removed");
                eq(0, a.node.connectedCount(), "no links");
            }
        });
        test("three computers all see each other's copies", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code); TestNode c = new TestNode("C", code)) {
                a.linkTo(b);
                a.linkTo(c);
                b.linkTo(c);
                check(waitFor(() -> a.node.connectedCount() == 2 && b.node.connectedCount() == 2 && c.node.connectedCount() == 2, 8000), "full mesh");
                a.copy("to everyone");
                check(waitFor(() -> "to everyone".equals(b.clipboard()) && "to everyone".equals(c.clipboard()), 4000), "both received");
                Thread.sleep(500);
                eq(1, b.node.getHistory().size(), "B has it once");
                eq(1, c.node.getHistory().size(), "C has it once");
            }
        });
        test("pause stops sending and receiving", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                a.node.setPaused(true);
                a.copy("while paused");
                Thread.sleep(600);
                eq(0, b.node.getHistory().size(), "nothing sent while paused");
                b.node.setPaused(true);
                a.node.setPaused(false);
                a.copy("b is paused");
                Thread.sleep(600);
                eq(0, b.node.getHistory().size(), "nothing received while paused");
            }
        });
        test("a copy over 1 MB is reported and not silently dropped", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                a.copy("x".repeat(Node.MAX_TEXT_BYTES + 10));
                check(waitFor(() -> !a.problems.isEmpty(), 3000), "user told");
                check(a.problems.get(0).contains("too large"), a.problems.get(0));
                a.copy("small again");
                check(waitFor(() -> "small again".equals(b.clipboard()), 4000), "link still works afterwards");
            }
        });
        test("a copy just under 1 MB is sent", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "connected");
                String big = "y".repeat(Node.MAX_TEXT_BYTES - 100);
                a.copy(big);
                check(waitFor(() -> big.equals(b.clipboard()), 8000), "arrived intact");
            }
        });
        test("something that is not ClipBridge connecting to the port is dropped safely", () -> {
            try (TestNode a = new TestNode("A", code)) {
                try (java.net.Socket s = new java.net.Socket("127.0.0.1", a.node.getPort())) {
                    s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes());
                    s.getOutputStream().flush();
                    s.setSoTimeout(5000);
                    // the server must close (read returns -1) or at worst send its own HELLO and then close
                    byte[] buf = new byte[4096];
                    int guard = 0;
                    while (s.getInputStream().read(buf) >= 0 && guard++ < 50) {
                        // drain
                    }
                } catch (java.net.SocketException resetIsFine) {
                    // closed with a reset: also fine
                }
                a.copy("still fine");
                eq(0, a.node.connectedCount(), "no link");
                check(a.node.getPort() > 0, "still listening");
            }
        });
        test("a stranger that sends a huge length is dropped without crashing the node", () -> {
            try (TestNode a = new TestNode("A", code); TestNode b = new TestNode("B", code)) {
                try (java.net.Socket s = new java.net.Socket("127.0.0.1", a.node.getPort())) {
                    java.io.DataOutputStream d = new java.io.DataOutputStream(s.getOutputStream());
                    d.writeByte(1);
                    d.writeInt(Integer.MAX_VALUE);
                    d.flush();
                    s.setSoTimeout(3000);
                    while (s.getInputStream().read() >= 0) {
                        // drain until closed
                    }
                } catch (java.net.SocketException resetIsFine) {
                    // fine
                }
                a.linkTo(b);
                check(waitFor(() -> connected(a, b), 5000), "node still works");
            }
        });
        test("a forged encrypted message from a stranger who knows the protocol is rejected", () -> {
            try (TestNode a = new TestNode("A", code)) {
                try (java.net.Socket s = new java.net.Socket("127.0.0.1", a.node.getPort())) {
                    java.io.DataOutputStream d = new java.io.DataOutputStream(s.getOutputStream());
                    java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
                    // send a perfectly valid HELLO that claims to have a code, then garbage as the "proof"
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    java.io.DataOutputStream h = new java.io.DataOutputStream(bo);
                    h.write(new byte[]{'C', 'B', 'R', '1'});
                    h.write(new byte[16]);
                    h.write(new byte[16]);
                    h.writeBoolean(true);
                    h.writeShort(1234);
                    h.writeUTF("Mallory");
                    d.writeByte(1);
                    d.writeInt(bo.size());
                    d.write(bo.toByteArray());
                    d.writeByte(2);
                    d.writeInt(40);
                    d.write(new byte[40]);
                    d.flush();
                    s.setSoTimeout(4000);
                    int type = -1;
                    try {
                        // skip the node's HELLO frame, then expect a REFUSE frame
                        in.readByte();
                        int len = in.readInt();
                        in.readFully(new byte[len]);
                        type = in.readByte();
                    } catch (IOException closed) {
                        // closing without a frame is also acceptable
                    }
                    check(type == 3 || type == -1, "must be refused, got frame type " + type);
                }
                check(waitFor(() -> !a.problems.isEmpty(), 3000), "the owner is told");
                check(a.problems.get(0).contains("Mallory has a different pairing code"), a.problems.get(0));
                eq(0, a.node.connectedCount(), "not linked");
            }
        });
        test("bad addresses are rejected with a plain message", () -> {
            try (TestNode a = new TestNode("A", code)) {
                mustThrow(IllegalArgumentException.class, () -> a.node.connectTo("", 5005), "empty host");
                mustThrow(IllegalArgumentException.class, () -> a.node.connectTo("   ", 5005), "blank host");
                mustThrow(IllegalArgumentException.class, () -> a.node.connectTo("1.2.3.4", 0), "port 0");
                mustThrow(IllegalArgumentException.class, () -> a.node.connectTo("1.2.3.4", 70000), "port too big");
                mustThrow(IllegalArgumentException.class, () -> a.node.connectTo("bad host", 5005), "space in host");
                mustThrow(IllegalArgumentException.class, () -> a.node.setPairingCode("ABC"), "short code");
            }
        });
        test("an address that does not answer shows as not reachable and the app stays usable", () -> {
            try (TestNode a = new TestNode("A", code)) {
                a.node.connectTo("127.0.0.1", 1); // nothing listens there
                check(waitFor(() -> a.node.peers().get(0).detail.contains("Not reachable"), 4000), "state shown");
                eq(Node.State.WAITING, a.node.peers().get(0).state, "waiting");
                a.copy("local copy still fine");
                check(waitFor(() -> a.node.getHistory().size() == 1, 3000), "history still records local copies");
            }
        });
        test("starting on a port that is already used gives a clear message", () -> {
            try (TestNode a = new TestNode("A", code)) {
                Node.Options o = new Node.Options();
                o.port = a.node.getPort();
                o.backend = new FileClipboardBackend(tmp.resolve("dup.txt"));
                o.discovery = false;
                Node second = new Node(o);
                try {
                    second.start();
                    throw new AssertionError("second start should fail");
                } catch (IOException e) {
                    check(e.getMessage().contains("already in use"), e.getMessage());
                } finally {
                    second.close();
                }
            }
        });
        test("automatic discovery finds a computer on the same network", () -> {
            Node.Options oa = new Node.Options();
            oa.name = "DiscA";
            oa.port = 0;
            oa.pairingCode = code;
            oa.backend = new FileClipboardBackend(tmp.resolve("da.txt"));
            oa.retryMs = 400;
            Node.Options ob = new Node.Options();
            ob.name = "DiscB";
            ob.port = 0;
            ob.pairingCode = code;
            ob.backend = new FileClipboardBackend(tmp.resolve("db.txt"));
            ob.retryMs = 400;
            try (Node na = new Node(oa); Node nb = new Node(ob)) {
                na.start();
                nb.start();
                if (na.getDiscoveryError() != null || nb.getDiscoveryError() != null) {
                    throw new SkipTest("multicast not available here: " + na.getDiscoveryError());
                }
                if (!waitFor(() -> na.connectedCount() == 1 && nb.connectedCount() == 1, 12000)) {
                    throw new SkipTest("no multicast delivery between copies in this environment");
                }
            }
        });
    }

    // ------------------------------------------------------------------ util

    static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp files; the OS cleans up later
                }
            });
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
