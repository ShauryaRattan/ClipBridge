package clipbridge.gui;

import clipbridge.core.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;

/** Shows each dialog for real (they are modal), screenshots it from a timer, then closes it. */
public final class DialogShots {
    interface Show {
        void run(JFrame owner) throws Exception;
    }

    static void capture(String out, String name, Show show) throws Exception {
        JFrame owner = new JFrame("owner");
        owner.setSize(300, 200);
        owner.setLocation(50, 50);
        owner.setVisible(true);
        javax.swing.Timer t = new javax.swing.Timer(700, e -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog && w.isShowing()) {
                    try {
                        BufferedImage img = new BufferedImage(w.getWidth(), w.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = img.createGraphics();
                        w.paint(g);
                        g.dispose();
                        ImageIO.write(img, "png", new File(out + "/" + name + ".png"));
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    w.dispose();
                }
            }
        });
        t.setRepeats(false);
        t.start();
        show.run(owner);
        owner.dispose();
    }

    public static void main(String[] args) throws Exception {
        String out = args[0];
        Path dir = Files.createTempDirectory("dlg");
        Node.Options o = new Node.Options();
        o.backend = new FileClipboardBackend(dir.resolve("c.txt"));
        o.discovery = false;
        o.port = 0;
        o.pairingCode = PairingCode.generate();
        Node node = new Node(o);
        node.start();
        Config cfg = Config.load(dir);
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                capture(out, "dlg-pairing", f -> new PairingDialog(f, node.getPairingCode()).setVisible(true));
                capture(out, "dlg-connect", f -> Dialogs.connectByAddress(f, node));
                capture(out, "dlg-settings", f -> Dialogs.settings(f, node, cfg));
                capture(out, "dlg-about", f -> Dialogs.about(f, dir));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        System.exit(0);
    }
}
