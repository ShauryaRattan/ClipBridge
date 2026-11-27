package clipbridge.core;

/** Where the "clipboard" lives: the real system clipboard, or a plain file (for tests and demos). */
public interface ClipboardBackend {
    /** The current text, or null when the clipboard is empty, holds something else, or is busy. */
    String get();

    void set(String text);
}
