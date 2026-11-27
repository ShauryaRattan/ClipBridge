package clipbridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Small settings file (config.properties) in the data folder: computer name, port, pairing code. */
public final class Config {
    private final Path file;
    private final Properties props = new Properties();

    private Config(Path file) {
        this.file = file;
    }

    public static Config load(Path dataDir) {
        Config c = new Config(dataDir.resolve("config.properties"));
        if (Files.isRegularFile(c.file)) {
            try (InputStream in = Files.newInputStream(c.file)) {
                c.props.load(in);
            } catch (IOException | IllegalArgumentException e) {
                c.props.clear(); // damaged file: fall back to defaults
            }
        }
        return c;
    }

    public synchronized String getName() {
        String n = props.getProperty("name", "").trim();
        return n.isEmpty() ? defaultName() : n;
    }

    public synchronized void setName(String name) {
        props.setProperty("name", name == null ? "" : name.trim());
    }

    public synchronized int getPort() {
        try {
            int p = Integer.parseInt(props.getProperty("port", "5005").trim());
            return p >= 1 && p <= 65535 ? p : 5005;
        } catch (NumberFormatException e) {
            return 5005;
        }
    }

    public synchronized void setPort(int port) {
        props.setProperty("port", Integer.toString(port));
    }

    /** The saved pairing code (normalized), or "" when none is set. */
    public synchronized String getPairingCode() {
        String code = PairingCode.normalize(props.getProperty("pairingCode", ""));
        return PairingCode.isValid(code) ? code : "";
    }

    public synchronized void setPairingCode(String code) {
        props.setProperty("pairingCode", PairingCode.normalize(code));
    }

    public synchronized void save() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "ClipBridge settings");
        }
    }

    public Path getFile() {
        return file;
    }

    private static String defaultName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.trim().isEmpty()) {
                return host.trim();
            }
        } catch (IOException ignored) {
            // fall through
        }
        String user = System.getProperty("user.name");
        return user == null || user.isEmpty() ? "My-PC" : user + "-PC";
    }
}
