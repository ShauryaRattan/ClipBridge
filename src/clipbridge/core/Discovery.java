package clipbridge.core;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds other ClipBridge computers on the same network with UDP multicast.
 * Every 3 seconds each computer announces "CBR1|id|tcpPort|name". The announcement contains
 * no clipboard data and no secret. Joining is done on every network adapter, because on Windows
 * a laptop often has Wi-Fi, Ethernet, VPN and virtual adapters at the same time.
 */
public final class Discovery implements Closeable {
    public interface Handler {
        void peerSeen(String host, int tcpPort, String nodeId, String name);
    }

    static final String GROUP = "239.255.77.88";
    static final int UDP_PORT = 45454;
    private static final Logger LOG = Logger.getLogger("clipbridge");

    private final String nodeId;
    private volatile String name;
    private final int tcpPort;
    private final Handler handler;
    private volatile boolean closed;
    private MulticastSocket receiveSocket;
    private MulticastSocket sendSocket;
    private final List<NetworkInterface> interfaces = new ArrayList<>();

    public Discovery(String nodeId, String name, int tcpPort, Handler handler) {
        this.nodeId = nodeId;
        this.name = name;
        this.tcpPort = tcpPort;
        this.handler = handler;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Starts listening and announcing. Throws IOException when the network does not allow it. */
    public void start() throws IOException {
        InetAddress group = InetAddress.getByName(GROUP);
        receiveSocket = new MulticastSocket(null);
        receiveSocket.setReuseAddress(true); // several copies on one computer can share the port
        receiveSocket.bind(new InetSocketAddress(UDP_PORT));
        sendSocket = new MulticastSocket();

        int joined = 0;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || !ni.supportsMulticast() || !hasIpv4(ni)) {
                        continue;
                    }
                    receiveSocket.joinGroup(new InetSocketAddress(group, UDP_PORT), ni);
                    interfaces.add(ni);
                    joined++;
                } catch (IOException skipThisAdapter) {
                    LOG.fine("Discovery: skipping adapter " + ni.getName() + ": " + skipThisAdapter);
                }
            }
        } catch (SocketException e) {
            throw new IOException("Cannot list network adapters: " + e.getMessage(), e);
        }
        if (joined == 0) {
            receiveSocket.close();
            sendSocket.close();
            throw new IOException("No network adapter that supports multicast is available");
        }

        Thread listener = new Thread(this::listenLoop, "discovery-listen");
        listener.setDaemon(true);
        listener.start();
        Thread announcer = new Thread(this::announceLoop, "discovery-announce");
        announcer.setDaemon(true);
        announcer.start();
    }

    private static boolean hasIpv4(NetworkInterface ni) {
        for (InterfaceAddress a : ni.getInterfaceAddresses()) {
            if (a.getAddress() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    private void announceLoop() {
        while (!closed) {
            try {
                byte[] data = ("CBR1|" + nodeId + "|" + tcpPort + "|" + name).getBytes(StandardCharsets.UTF_8);
                InetAddress group = InetAddress.getByName(GROUP);
                for (NetworkInterface ni : interfaces) {
                    try {
                        sendSocket.setNetworkInterface(ni);
                        sendSocket.send(new DatagramPacket(data, data.length, group, UDP_PORT));
                    } catch (IOException adapterProblem) {
                        LOG.fine("Discovery: send failed on " + ni.getName() + ": " + adapterProblem);
                    }
                }
            } catch (IOException e) {
                LOG.fine("Discovery announce failed: " + e);
            }
            sleep(3000);
        }
    }

    private void listenLoop() {
        byte[] buf = new byte[1024];
        while (!closed) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                receiveSocket.receive(p);
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                String[] parts = text.split("\\|", 4);
                if (parts.length != 4 || !parts[0].equals("CBR1") || parts[1].equals(nodeId)) {
                    continue;
                }
                int port = Integer.parseInt(parts[2]);
                if (port < 1 || port > 65535 || parts[1].length() != 32) {
                    continue;
                }
                handler.peerSeen(p.getAddress().getHostAddress(), port, parts[1], parts[3]);
            } catch (NumberFormatException ignoredBadPacket) {
                // somebody else's packet on this port
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.FINE, "Discovery receive failed: " + e);
                    sleep(500);
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (receiveSocket != null) {
            receiveSocket.close();
        }
        if (sendSocket != null) {
            sendSocket.close();
        }
    }
}
