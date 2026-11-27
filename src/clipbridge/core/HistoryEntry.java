package clipbridge.core;

import java.io.Serializable;

/** One copied text. Saved to disk with Java serialization. */
public final class HistoryEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long time;
    private final String source;
    private final String text;

    public HistoryEntry(long time, String source, String text) {
        this.time = time;
        this.source = source;
        this.text = text;
    }

    public long getTime() {
        return time;
    }

    /** "This computer" or the name of the computer it came from. */
    public String getSource() {
        return source;
    }

    public String getText() {
        return text;
    }

    /** The text on one line, cut to at most {@code max} characters. */
    public String preview(int max) {
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, Math.max(0, max - 1)) + "…";
    }
}
