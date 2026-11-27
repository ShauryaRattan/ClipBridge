package clipbridge.gui;

import clipbridge.Branding;
import clipbridge.core.*;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Wires settings, history, the network node and the window together, then shows the window. */
public final class AppLauncher {
    private AppLauncher() {
    }

    public static void launch(Path dataDir, String nameOverride, Integer portOverride, Path clipFile, boolean discovery) {
        Logger.getLogger("clipbridge").setLevel(Level.INFO);
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(Branding.APP_NAME + " needs a desktop to show its window.");
            System.exit(2);
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default Swing look is fine
        }
        SwingUtilities.invokeLater(() -> start(dataDir, nameOverride, portOverride, clipFile, discovery));
    }

    private static void start(Path dataDir, String nameOverride, Integer portOverride, Path clipFile, boolean discovery) {
        Config config = Config.load(dataDir);
        if (nameOverride != null) {
            config.setName(nameOverride);
        }
        if (portOverride != null) {
            config.setPort(portOverride);
        }

        Node.Options o = new Node.Options();
        o.name = config.getName();
        o.port = config.getPort();
        o.pairingCode = config.getPairingCode();
        o.history = History.load(dataDir.resolve("history.bin"));
        o.backend = clipFile != null ? new FileClipboardBackend(clipFile) : new SystemClipboardBackend();
        o.discovery = discovery;
        Node node = new Node(o);
        try {
            node.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), Branding.APP_NAME + " cannot start",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        MainWindow window = new MainWindow(node, config, dataDir);
        Tray tray = Tray.install(window, node);
        window.setTray(tray);
        window.setVisible(true);
        if (node.getDiscoveryError() != null) {
            JOptionPane.showMessageDialog(window,
                    "Automatic discovery is not available on this network (" + node.getDiscoveryError() + ").\n"
                            + "You can still connect with Tools > Connect by IP address.",
                    Branding.APP_NAME, JOptionPane.INFORMATION_MESSAGE);
        }
        window.promptForCodeIfMissing();
    }
}
