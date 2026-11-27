package clipbridge.gui;

import clipbridge.Branding;
import clipbridge.core.Node;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** The icon next to the clock. Closing the window hides it here, so the sync keeps running. */
final class Tray {
    private final SystemTray systemTray;
    private final TrayIcon icon;
    private final MainWindow window;
    private final Node node;
    private final MenuItem pauseItem = new MenuItem();
    private boolean hintShown;

    private Tray(SystemTray systemTray, TrayIcon icon, MainWindow window, Node node) {
        this.systemTray = systemTray;
        this.icon = icon;
        this.window = window;
        this.node = node;
    }

    /** Returns null when the desktop has no system tray (the window then simply quits on close). */
    static Tray install(MainWindow window, Node node) {
        try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                return null;
            }
            SystemTray st = SystemTray.getSystemTray();
            Dimension size = st.getTrayIconSize();
            Image img = Icons.logo(Math.max(16, Math.max(size.width, size.height)));
            PopupMenu menu = new PopupMenu();
            TrayIcon icon = new TrayIcon(img, Branding.APP_NAME, menu);
            icon.setImageAutoSize(true);
            Tray tray = new Tray(st, icon, window, node);

            MenuItem open = new MenuItem("Open " + Branding.APP_NAME);
            open.addActionListener((ActionEvent e) -> tray.showWindow());
            tray.pauseItem.addActionListener((ActionEvent e) -> {
                node.setPaused(!node.isPaused());
            });
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener((ActionEvent e) -> window.exitApp());
            menu.add(open);
            menu.add(tray.pauseItem);
            menu.addSeparator();
            menu.add(exit);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 1) {
                        tray.showWindow();
                    }
                }
            });
            st.add(icon);
            tray.update();
            return tray;
        } catch (AWTException | UnsupportedOperationException | SecurityException e) {
            return null;
        }
    }

    void showWindow() {
        EventQueue.invokeLater(() -> {
            window.setVisible(true);
            window.setExtendedState(Frame.NORMAL);
            window.toFront();
            window.requestFocus();
        });
    }

    void update() {
        pauseItem.setLabel(node.isPaused() ? "Resume sync" : "Pause sync");
        icon.setToolTip(Branding.APP_NAME + " - " + (node.isPaused() ? "paused"
                : node.connectedCount() + " computer(s) connected"));
    }

    void notifyReceived(String from, String text) {
        if (window.isVisible() && window.isActive()) {
            return; // the user is looking at the window already
        }
        String one = text.replaceAll("\\s+", " ").trim();
        if (one.length() > 80) {
            one = one.substring(0, 79) + "…";
        }
        icon.displayMessage("Copied from " + from, one, TrayIcon.MessageType.INFO);
    }

    void firstHideHint() {
        if (!hintShown) {
            hintShown = true;
            icon.displayMessage(Branding.APP_NAME + " is still running",
                    "It keeps syncing from the tray. Right-click the icon and choose Exit to quit.",
                    TrayIcon.MessageType.INFO);
        }
    }

    void remove() {
        systemTray.remove(icon);
    }
}
