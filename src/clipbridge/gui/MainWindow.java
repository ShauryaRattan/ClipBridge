package clipbridge.gui;

import clipbridge.Branding;
import clipbridge.core.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/** The main ClipBridge window: computers, sending text, history, and status. */
@SuppressWarnings("serial")
public final class MainWindow extends JFrame {
    private static final Color GREEN = new Color(0x15803D);
    private static final Color ORANGE = new Color(0xB45309);
    private static final Color RED = new Color(0xB91C1C);
    private static final Color MUTED = new Color(0x64748B);

    private final Node node;
    private final Config config;
    private final Path dataDir;
    private Tray tray;

    private final PeerTableModel peerModel = new PeerTableModel();
    private final JTable peerTable = new JTable(peerModel);
    private final JPanel peerCards = new JPanel(new CardLayout());
    private final JLabel encryptionLabel = new JLabel();
    private final JLabel thisComputer = new JLabel();
    private final JToggleButton pauseButton = new JToggleButton("Pause sync");
    private final JTextField sendField = new JTextField();
    private final JTextField searchField = new JTextField(14);
    private final DefaultListModel<HistoryEntry> historyModel = new DefaultListModel<>();
    private final JList<HistoryEntry> historyList = new JList<>(historyModel);
    private final JLabel statusBar = new JLabel(" ");
    private volatile boolean refreshQueued;

    public MainWindow(Node node, Config config, Path dataDir) {
        super(Branding.APP_NAME);
        this.node = node;
        this.config = config;
        this.dataDir = dataDir;
        setIconImages(Icons.windowIcons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closePressed();
            }
        });
        setJMenuBar(buildMenu());

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        root.add(buildHeader(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildComputersPanel(), buildHistoryPanel());
        split.setResizeWeight(0.35);
        split.setBorder(null);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);
        statusBar.setForeground(MUTED);
        statusBar.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);

        node.addListener(new Node.Listener() {
            @Override
            public void onPeersChanged() {
                scheduleRefresh();
            }

            @Override
            public void onClipboard(String source, String text, boolean fromRemote) {
                scheduleRefresh();
                if (fromRemote && tray != null) {
                    SwingUtilities.invokeLater(() -> tray.notifyReceived(source, text));
                }
            }

            @Override
            public void onProblem(String message) {
                SwingUtilities.invokeLater(() -> setStatus(message, true));
            }
        });

        setSize(760, 640);
        setMinimumSize(new Dimension(620, 520));
        setLocationRelativeTo(null);
        refreshAll();
        new javax.swing.Timer(2000, e -> refreshPeersOnly()).start(); // keeps "last seen" style details fresh
    }

    void setTray(Tray tray) {
        this.tray = tray;
    }

    // ------------------------------------------------------------------ building

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        JMenuItem exit = new JMenuItem("Exit ClipBridge");
        exit.addActionListener(e -> exitApp());
        file.add(exit);

        JMenu tools = new JMenu("Tools");
        tools.setMnemonic('T');
        tools.add(item("Pairing code...", e -> openPairing()));
        tools.add(item("Connect by IP address...", e -> Dialogs.connectByAddress(this, node)));
        tools.add(item("Settings...", e -> {
            if (Dialogs.settings(this, node, config)) {
                refreshAll();
            }
        }));
        tools.addSeparator();
        tools.add(item("Reconnect all", e -> node.dropConnections()));

        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        help.add(item("How to use", e -> Dialogs.howTo(this)));
        help.add(item("About " + Branding.APP_NAME, e -> Dialogs.about(this, dataDir)));
        bar.add(file);
        bar.add(tools);
        bar.add(help);
        return bar;
    }

    private static JMenuItem item(String text, ActionListener l) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(l);
        return i;
    }

    private JComponent buildHeader() {
        JLabel logo = new JLabel(new ImageIcon(Icons.logo(52)));
        JLabel title = new JLabel(Branding.APP_NAME);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel tag = new JLabel(Branding.TAGLINE);
        tag.setForeground(MUTED);
        JPanel names = new JPanel();
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        names.setOpaque(false);
        names.add(title);
        names.add(tag);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.add(logo);
        left.add(names);

        encryptionLabel.setFont(encryptionLabel.getFont().deriveFont(Font.BOLD));
        thisComputer.setForeground(MUTED);
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setOpaque(false);
        encryptionLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        thisComputer.setAlignmentX(Component.RIGHT_ALIGNMENT);
        right.add(encryptionLabel);
        right.add(Box.createVerticalStrut(2));
        right.add(thisComputer);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildComputersPanel() {
        peerTable.setRowHeight(28);
        peerTable.setFillsViewportHeight(true);
        peerTable.setShowGrid(false);
        peerTable.setIntercellSpacing(new Dimension(0, 0));
        peerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        peerTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        peerTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        peerTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        peerTable.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        peerTable.getAccessibleContext().setAccessibleName("Other computers");

        JLabel empty = new JLabel("<html><div style='text-align:center;width:420px'>"
                + "<b>No other computers yet.</b><br>Start " + Branding.APP_NAME + " on another computer on the same "
                + "network and give it the same pairing code. It will appear here by itself.<br>"
                + "If it does not, use <i>Connect by IP address</i>.</div></html>", SwingConstants.CENTER);
        empty.setForeground(MUTED);
        peerCards.add(new JScrollPane(peerTable), "table");
        peerCards.add(empty, "empty");

        JButton pairing = new JButton("Pairing code...");
        pairing.addActionListener(e -> openPairing());
        JButton connect = new JButton("Connect by IP address...");
        connect.addActionListener(e -> Dialogs.connectByAddress(this, node));
        pauseButton.addActionListener(e -> {
            node.setPaused(pauseButton.isSelected());
            refreshAll();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(pairing);
        buttons.add(connect);
        buttons.add(pauseButton);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(sectionTitle("Computers"), BorderLayout.NORTH);
        p.add(peerCards, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildHistoryPanel() {
        sendField.setToolTipText("Type or paste text and press Enter to put it on every connected computer's clipboard");
        sendField.addActionListener(e -> sendTyped());
        JButton send = new JButton("Send to all");
        send.addActionListener(e -> sendTyped());
        JPanel sendRow = new JPanel(new BorderLayout(8, 0));
        sendRow.add(new JLabel("Send text:"), BorderLayout.WEST);
        sendRow.add(sendField, BorderLayout.CENTER);
        sendRow.add(send, BorderLayout.EAST);

        searchField.setToolTipText("Search the history");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshHistory();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshHistory();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshHistory();
            }
        });
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.add(sectionTitle("History"), BorderLayout.WEST);
        JPanel search = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        search.add(new JLabel("Search:"));
        search.add(searchField);
        titleRow.add(search, BorderLayout.EAST);

        historyList.setCellRenderer(new HistoryRenderer());
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.getAccessibleContext().setAccessibleName("Clipboard history");
        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    copySelected();
                }
            }
        });
        historyList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                    deleteSelected();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    copySelected();
                }
            }
        });

        JButton copy = new JButton("Copy again");
        copy.setToolTipText("Put the selected text on the clipboard of every connected computer (double-click)");
        copy.addActionListener(e -> copySelected());
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> deleteSelected());
        JButton clear = new JButton("Clear all");
        clear.addActionListener(e -> {
            if (node.getHistory().size() > 0 && JOptionPane.showConfirmDialog(this,
                    "Delete all " + node.getHistory().size() + " items from the history on this computer?",
                    "Clear history", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                node.getHistory().clear();
                refreshHistory();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(copy);
        buttons.add(delete);
        buttons.add(clear);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(sendRow, BorderLayout.NORTH);
        top.add(titleRow, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(historyList), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return p;
    }

    private static JLabel sectionTitle(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        return l;
    }

    // ------------------------------------------------------------------ actions

    private void openPairing() {
        PairingDialog d = new PairingDialog(this, node.getPairingCode());
        d.setVisible(true);
        String result = d.getResult();
        if (result == null) {
            return;
        }
        try {
            node.setPairingCode(result);
            config.setPairingCode(result);
            config.save();
            setStatus(result.isEmpty() ? "Pairing code removed. This computer will not connect to others."
                    : "Pairing code saved. Computers with the same code will connect.", false);
        } catch (IllegalArgumentException ex) {
            setStatus(ex.getMessage(), true);
        } catch (IOException ex) {
            setStatus("The code is active, but it could not be saved for the next start: " + ex.getMessage(), true);
        }
        refreshAll();
    }

    private void sendTyped() {
        String text = sendField.getText();
        if (text.trim().isEmpty()) {
            setStatus("Type some text first.", true);
            return;
        }
        node.shareText(text);
        sendField.setText("");
        setStatus(node.connectedCount() == 0
                ? "Copied here, but no other computer is connected."
                : "Sent to " + node.connectedCount() + " computer(s).", false);
    }

    private void copySelected() {
        HistoryEntry e = historyList.getSelectedValue();
        if (e == null) {
            setStatus("Select an item in the history first.", true);
            return;
        }
        node.shareText(e.getText());
        setStatus("Copied. It is now on the clipboard"
                + (node.connectedCount() > 0 ? " of every connected computer." : " of this computer."), false);
    }

    private void deleteSelected() {
        HistoryEntry e = historyList.getSelectedValue();
        if (e != null) {
            int index = historyList.getSelectedIndex();
            node.getHistory().remove(e);
            refreshHistory();
            if (historyModel.size() > 0) {
                historyList.setSelectedIndex(Math.min(index, historyModel.size() - 1));
            }
        }
    }

    private void closePressed() {
        if (tray != null) {
            setVisible(false);
            tray.firstHideHint();
        } else {
            exitApp();
        }
    }

    /** Stops everything and ends the program. */
    public void exitApp() {
        if (tray != null) {
            tray.remove();
        }
        node.close();
        dispose();
        System.exit(0);
    }

    /** First start: the user cannot do anything useful without a code, so ask for it. */
    public void promptForCodeIfMissing() {
        if (!node.hasPairingCode()) {
            JOptionPane.showMessageDialog(this,
                    "Welcome to " + Branding.APP_NAME + "!\n\nFirst choose a pairing code. Click \"Generate new\" on your first "
                            + "computer, and type the same code on your other computers.",
                    "Welcome", JOptionPane.INFORMATION_MESSAGE, new ImageIcon(Icons.logo(48)));
            openPairing();
        }
    }

    // ------------------------------------------------------------------ refreshing

    private void scheduleRefresh() {
        if (!refreshQueued) {
            refreshQueued = true;
            SwingUtilities.invokeLater(() -> {
                refreshQueued = false;
                refreshAll();
            });
        }
    }

    private void refreshAll() {
        refreshHeader();
        refreshPeersOnly();
        refreshHistory();
    }

    private void refreshHeader() {
        if (!node.hasPairingCode()) {
            encryptionLabel.setText("Not encrypted: no pairing code yet");
            encryptionLabel.setForeground(ORANGE);
        } else if (node.isPaused()) {
            encryptionLabel.setText("Paused");
            encryptionLabel.setForeground(ORANGE);
        } else {
            encryptionLabel.setText("Encrypted (AES-256-GCM)");
            encryptionLabel.setForeground(GREEN);
        }
        pauseButton.setSelected(node.isPaused());
        pauseButton.setText(node.isPaused() ? "Resume sync" : "Pause sync");
        thisComputer.setText("This computer: " + node.getName() + "   " + addressText());
        if (tray != null) {
            tray.update();
        }
    }

    private String addressText() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // no addresses to show
        }
        String first = ips.isEmpty() ? "" : ips.get(0) + ":";
        return "(" + first + node.getPort() + ")";
    }

    private void refreshPeersOnly() {
        peerModel.set(node.peers());
        ((CardLayout) peerCards.getLayout()).show(peerCards, peerModel.getRowCount() == 0 ? "empty" : "table");
        int n = node.connectedCount();
        if (statusBar.getText().trim().isEmpty() || statusBar.getText().startsWith("Connected to")
                || statusBar.getText().startsWith("Not connected")) {
            statusBar.setForeground(MUTED);
            statusBar.setText(n == 0 ? "Not connected to any other computer." : "Connected to " + n + " computer" + (n == 1 ? "" : "s") + ".");
        }
    }

    private void refreshHistory() {
        HistoryEntry keep = historyList.getSelectedValue();
        List<HistoryEntry> list = node.getHistory().search(searchField.getText());
        historyModel.clear();
        for (HistoryEntry e : list) {
            historyModel.addElement(e);
        }
        if (keep != null) {
            int i = list.indexOf(keep);
            if (i >= 0) {
                historyList.setSelectedIndex(i);
            }
        }
    }

    private void setStatus(String message, boolean problem) {
        statusBar.setForeground(problem ? RED : MUTED);
        statusBar.setText(message);
    }

    // ------------------------------------------------------------------ table and list pieces

    @SuppressWarnings("serial")
    private static final class PeerTableModel extends AbstractTableModel {
        private final String[] columns = {"Computer", "Address", "Status"};
        private List<Node.PeerStatus> rows = new ArrayList<>();

        void set(List<Node.PeerStatus> newRows) {
            rows = newRows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Node.PeerStatus p = rows.get(r);
            switch (c) {
                case 0:
                    return p.name;
                case 1:
                    return p.host + ":" + p.port;
                default:
                    return p;
            }
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Node.PeerStatus p = (Node.PeerStatus) v;
            super.getTableCellRendererComponent(t, p.detail, sel, focus, r, c);
            String dot = "● ";
            switch (p.state) {
                case CONNECTED:
                    setForeground(sel ? Color.WHITE : GREEN);
                    break;
                case REFUSED:
                    setForeground(sel ? Color.WHITE : RED);
                    break;
                default:
                    setForeground(sel ? Color.WHITE : ORANGE);
            }
            setText(dot + p.detail);
            setToolTipText(p.detail);
            return this;
        }
    }

    private static final class HistoryRenderer extends JPanel implements ListCellRenderer<HistoryEntry> {
        private static final long serialVersionUID = 1L;
        private final JLabel meta = new JLabel();
        private final JLabel text = new JLabel();
        private final SimpleDateFormat today = new SimpleDateFormat("HH:mm:ss");
        private final SimpleDateFormat older = new SimpleDateFormat("d MMM HH:mm");

        HistoryRenderer() {
            super(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 11f));
            add(meta, BorderLayout.NORTH);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends HistoryEntry> list, HistoryEntry e, int index,
                                                      boolean selected, boolean focus) {
            boolean sameDay = new SimpleDateFormat("yyyyMMdd").format(new Date(e.getTime()))
                    .equals(new SimpleDateFormat("yyyyMMdd").format(new Date()));
            String when = (sameDay ? today : older).format(new Date(e.getTime()));
            meta.setText(when + "  ·  " + e.getSource());
            text.setText(e.preview(110));
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            text.setForeground(fg);
            meta.setForeground(selected ? fg : MUTED);
            setToolTipText("<html><div style='width:360px'>" + escape(e.preview(600)) + "</div></html>");
            return this;
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
