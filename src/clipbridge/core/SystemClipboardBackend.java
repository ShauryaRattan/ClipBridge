package clipbridge.core;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/** The real clipboard of the desktop. Windows can report it as busy for a moment, so reads and writes retry. */
public final class SystemClipboardBackend implements ClipboardBackend {

    @Override
    public String get() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (!cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    return null;
                }
                return (String) cb.getData(DataFlavor.stringFlavor);
            } catch (IllegalStateException | UnsupportedFlavorException | IOException busy) {
                pause(30);
            }
        }
        return null;
    }

    @Override
    public void set(String text) {
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                return;
            } catch (IllegalStateException busy) {
                pause(40);
            }
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
