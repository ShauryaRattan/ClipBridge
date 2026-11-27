package clipbridge.gui;

import clipbridge.Branding;
import clipbridge.core.Config;
import clipbridge.core.Node;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;

/** The small dialogs: connect by address, settings, about. */
final class Dialogs {
    private Dialogs() {
    }

    private static final Color ERROR = new Color(0xB91C1C);

    /** Add a computer by IP address or name, for networks where automatic discovery is blocked. */
    static void connectByAddress(Window owner, Node node) {
        JDialog d = new JDialog(owner, "Connect by IP address", Dialog.ModalityType.APPLICATION_MODAL);
        d.setIconImages(Icons.windowIcons());
        JTextField host = new JTextField(18);
        JTextField port = new JTextField(Integer.toString(node.getPort()), 6);
        JLabel error = new JLabel(" ");
        error.setForeground(ERROR);

        JLabel help = wrapped("Use this if the other computer does not appear by itself (some Wi-Fi networks block "
                + "discovery). On that computer, ClipBridge shows its address at the top right of its window, next to "
                + "\"This computer\". Both computers still need the same pairing code.", 400);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("IP address or name:"), c);
        c.gridx = 1;
        form.add(host, c);
        c.gridx = 0;
        c.gridy = 1;
        form.add(new JLabel("Port:"), c);
        c.gridx = 1;
        form.add(port, c);

        JButton connect = new JButton("Connect");
        JButton cancel = new JButton("Cancel");
        connect.addActionListener(e -> {
            int p;
            try {
                p = Integer.parseInt(port.getText().trim());
            } catch (NumberFormatException ex) {
                error.setText("The port must be a number, for example 5005.");
                return;
            }
            try {
                node.connectTo(host.getText(), p);
                d.dispose();
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
            }
        });
        cancel.addActionListener(e -> d.dispose());
        d.getRootPane().setDefaultButton(connect);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(connect);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        root.add(help, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout());
        mid.add(form, BorderLayout.CENTER);
        mid.add(error, BorderLayout.SOUTH);
        root.add(mid, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /** Computer name and port. Returns true when something was saved. */
    static boolean settings(Window owner, Node node, Config config) {
        JDialog d = new JDialog(owner, "Settings", Dialog.ModalityType.APPLICATION_MODAL);
        d.setIconImages(Icons.windowIcons());
        JTextField name = new JTextField(config.getName(), 18);
        JTextField port = new JTextField(Integer.toString(config.getPort()), 6);
        JLabel error = new JLabel(" ");
        error.setForeground(ERROR);
        final boolean[] saved = {false};

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Name of this computer:"), c);
        c.gridx = 1;
        form.add(name, c);
        c.gridx = 0;
        c.gridy = 1;
        form.add(new JLabel("Port:"), c);
        c.gridx = 1;
        form.add(port, c);

        JLabel note = wrapped("The name is what your other computers see. A new port takes effect the next time you "
                + "start ClipBridge, and all your computers should use the same port.", 380);

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> {
            String n = name.getText().trim();
            if (n.isEmpty()) {
                error.setText("Enter a name for this computer.");
                return;
            }
            int p;
            try {
                p = Integer.parseInt(port.getText().trim());
            } catch (NumberFormatException ex) {
                error.setText("The port must be a number from 1 to 65535.");
                return;
            }
            if (p < 1 || p > 65535) {
                error.setText("The port must be a number from 1 to 65535.");
                return;
            }
            config.setName(n);
            config.setPort(p);
            try {
                config.save();
            } catch (IOException ex) {
                error.setText("Could not save the settings: " + ex.getMessage());
                return;
            }
            node.setName(n);
            saved[0] = true;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        d.getRootPane().setDefaultButton(save);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(save);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        JPanel mid = new JPanel(new BorderLayout());
        mid.add(form, BorderLayout.NORTH);
        mid.add(error, BorderLayout.SOUTH);
        root.add(note, BorderLayout.NORTH);
        root.add(mid, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return saved[0];
    }

    static void about(Window owner, Path dataDir) {
        JDialog d = new JDialog(owner, "About " + Branding.APP_NAME, Dialog.ModalityType.APPLICATION_MODAL);
        d.setIconImages(Icons.windowIcons());
        JLabel logo = new JLabel(new ImageIcon(Icons.logo(96)));
        JLabel title = new JLabel(Branding.APP_NAME + " " + Branding.VERSION);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel body = wrapped(Branding.TAGLINE + ".\n\n"
                + "Copy text on one computer and paste it on another. Computers on the same network find each "
                + "other automatically, and everything they send is encrypted with AES-256-GCM using your pairing code.\n\n"
                + "Made by " + Branding.AUTHOR + " in Java (Swing, sockets, threads, serialization).\n"
                + "Java " + System.getProperty("java.version") + "\n"
                + "Settings and history: " + dataDir, 420);
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> d.dispose());
        d.getRootPane().setDefaultButton(ok);

        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        head.add(logo);
        head.add(title);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(ok);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    static void howTo(Window owner) {
        String msg = "1.  On your first computer open Pairing code... and click Generate new, then Save.\n"
                + "2.  On your other computers open Pairing code... and type the same code, then Save.\n"
                + "3.  The computers appear in the list by themselves and show \"Connected, encrypted\".\n"
                + "4.  Copy text on any computer (Ctrl+C) and paste it on another (Ctrl+V).\n\n"
                + "If a computer does not appear, use Connect by IP... and type its address.\n"
                + "If it says \"different pairing code\", the codes do not match exactly.\n"
                + "Only text up to 1 MB is shared. Closing the window keeps ClipBridge running in the tray.";
        JOptionPane.showMessageDialog(owner, msg, "How to use " + Branding.APP_NAME, JOptionPane.INFORMATION_MESSAGE,
                new ImageIcon(Icons.logo(48)));
    }

    /** Wrapped text with a fixed width. (A wrapping JTextArea is measured at the wrong width by pack().) */
    static JLabel wrapped(String s, int widthPx) {
        String html = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        JLabel l = new JLabel("<html><div style='width:" + widthPx + "px'>" + html + "</div></html>");
        l.setVerticalAlignment(SwingConstants.TOP);
        return l;
    }
}
