package clipbridge.gui;

import clipbridge.core.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;

/** Opens the real windows for two computers, pairs them, and saves screenshots. Run under xvfb-run. */
public final class GuiSmoke {
    static void shot(Window w, String file) throws Exception {
        BufferedImage img = new BufferedImage(w.getWidth(), w.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        w.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(file));
    }

    static Node make(Path dir, String name, String code) throws Exception {
        Node.Options o = new Node.Options();
        o.name = name;
        o.port = 0;
        o.pairingCode = code;
        o.backend = new FileClipboardBackend(dir.resolve(name + ".txt"));
        o.history = History.inMemory();
        o.discovery = false;
        o.pollMs = 50;
        o.retryMs = 500;
        o.quickRetryMs = 200;
        Node n = new Node(o);
        n.start();
        return n;
    }

    public static void main(String[] args) throws Exception {
        String out = args[0];
        Path dir = Files.createTempDirectory("smoke");
        String code = PairingCode.generate();
        Node a = make(dir, "OFFICE-PC", code);
        Node b = make(dir, "LAPTOP", code);
        Node c = make(dir, "ODD-PC", PairingCode.generate());
        MainWindow[] win = new MainWindow[2];
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            Config cfg = Config.load(dir.resolve("cfg"));
            win[0] = new MainWindow(a, cfg, dir);
            win[0].setLocation(0, 0);
            win[0].setVisible(true);
        });
        Thread.sleep(500);
        shot(win[0], out + "/main-empty.png");

        a.connectTo("127.0.0.1", b.getPort());
        a.connectTo("127.0.0.1", c.getPort());
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end && a.connectedCount() < 1) {
            Thread.sleep(50);
        }
        a.shareText("Meeting moved to 4 pm, room 12");
        Thread.sleep(300);
        b.shareText("Reply from LAPTOP: ok ✓");
        Thread.sleep(300);
        a.shareText("https://example.com/a-very-long-link/that/goes/on/and/on/and/on/and/on/and/on/and/on/and/on/and/on/and/on/forever");
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> { });
        shot(win[0], out + "/main-connected.png");

        final PairingDialog[] pd = new PairingDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            pd[0] = new PairingDialog(win[0], code);
            pd[0].setLocation(100, 100);
            pd[0].setVisible(false);
        });
        shot(pd[0], out + "/pairing.png");
        System.out.println("connected=" + a.connectedCount() + " peers=" + a.peers().size());
        for (Node.PeerStatus p : a.peers()) {
            System.out.println("  " + p.name + " | " + p.state + " | " + p.detail);
        }
        System.exit(0);
    }
}
