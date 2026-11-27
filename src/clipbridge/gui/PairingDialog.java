package clipbridge.gui;

import clipbridge.core.PairingCode;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/** Create or enter the pairing code. Returns the code through {@link #getResult()} (null = cancelled). */
@SuppressWarnings("serial")
final class PairingDialog extends JDialog {
    private final JTextField field = new JTextField(22);
    private final JLabel error = new JLabel(" ");
    private String result;
    private boolean removed;

    PairingDialog(Window owner, String currentCode) {
        super(owner, "Pairing code", ModalityType.APPLICATION_MODAL);
        setIconImages(Icons.windowIcons());

        JLabel help = Dialogs.wrapped(
                "All your computers must use the same pairing code. It is the password that encrypts "
                        + "everything they send each other, and a computer without it is refused.\n\n"
                        + "On the first computer click \"Generate new\", then type that same code on your other computers.", 420);

        field.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        field.setHorizontalAlignment(JTextField.CENTER);
        field.setText(currentCode.isEmpty() ? "" : PairingCode.format(currentCode));
        field.getAccessibleContext().setAccessibleName("Pairing code");
        error.setForeground(new Color(0xB91C1C));

        JButton generate = new JButton("Generate new");
        generate.addActionListener(e -> {
            field.setText(PairingCode.format(PairingCode.generate()));
            error.setText(" ");
        });
        JButton copy = new JButton("Copy code");
        copy.addActionListener(e -> {
            if (PairingCode.isValid(field.getText())) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(PairingCode.format(field.getText())), null);
                error.setForeground(new Color(0x15803D));
                error.setText("Code copied. Paste it on your other computer.");
            } else {
                error.setForeground(new Color(0xB91C1C));
                error.setText("There is no valid code to copy yet.");
            }
        });
        JButton remove = new JButton("Remove code");
        remove.setEnabled(!currentCode.isEmpty());
        remove.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Without a pairing code this computer cannot connect to any other.\nRemove it?",
                    "Remove pairing code", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                removed = true;
                result = "";
                dispose();
            }
        });
        JButton save = new JButton("Save");
        save.addActionListener(e -> trySave());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        tools.add(generate);
        tools.add(copy);
        tools.add(remove);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.add(cancel);
        bottom.add(save);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{help, field, tools, error, bottom}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        center.add(help);
        center.add(Box.createVerticalStrut(12));
        center.add(field);
        center.add(Box.createVerticalStrut(10));
        center.add(tools);
        center.add(Box.createVerticalStrut(6));
        center.add(error);
        center.add(Box.createVerticalStrut(6));
        center.add(bottom);
        center.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        setContentPane(center);
        pack();
        setLocationRelativeTo(owner);
    }

    private void trySave() {
        String problem = PairingCode.explainProblem(field.getText());
        if (problem != null) {
            error.setForeground(new Color(0xB91C1C));
            error.setText(problem);
            return;
        }
        result = PairingCode.normalize(field.getText());
        dispose();
    }

    /** The normalized new code, "" if the user removed it, or null if the dialog was cancelled. */
    String getResult() {
        return removed ? "" : result;
    }
}
