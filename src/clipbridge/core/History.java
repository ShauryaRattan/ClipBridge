package clipbridge.core;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The list of recent clipboard texts, newest first. Kept in memory and saved to a file. */
public final class History {
    public static final int MAX_ENTRIES = 100;
    private static final Logger LOG = Logger.getLogger("clipbridge");

    private final Path file; // null = memory only (used by tests)
    private final List<HistoryEntry> entries = new ArrayList<>();
    private final AtomicBoolean savePending = new AtomicBoolean();
    private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "history-saver");
        t.setDaemon(true);
        return t;
    });

    private History(Path file) {
        this.file = file;
    }

    public static History inMemory() {
        return new History(null);
    }

    /** Loads the file if it is there and readable; a missing or damaged file gives an empty history. */
    public static History load(Path file) {
        History h = new History(file);
        if (Files.isRegularFile(file)) {
            try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                // The file is on the user's own disk, but accept only the classes we wrote ourselves.
                in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                        "clipbridge.core.HistoryEntry;java.util.ArrayList;java.lang.*;!*"));
                Object o = in.readObject();
                if (o instanceof List) {
                    for (Object item : (List<?>) o) {
                        if (item instanceof HistoryEntry) {
                            h.entries.add((HistoryEntry) item);
                        }
                    }
                }
            } catch (IOException | ClassNotFoundException | RuntimeException e) {
                LOG.log(Level.WARNING, "History file could not be read, starting empty: " + e);
                h.entries.clear();
            }
        }
        return h;
    }

    /** Returns false when the text is the same as the newest entry (nothing added). */
    public synchronized boolean add(String source, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (!entries.isEmpty() && entries.get(0).getText().equals(text)) {
            return false;
        }
        entries.add(0, new HistoryEntry(System.currentTimeMillis(), source, text));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        scheduleSave();
        return true;
    }

    public synchronized List<HistoryEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    /** Entries whose text or source contains the query (ignoring case). An empty query returns all. */
    public synchronized List<HistoryEntry> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return snapshot();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<HistoryEntry> out = new ArrayList<>();
        for (HistoryEntry e : entries) {
            if (e.getText().toLowerCase(Locale.ROOT).contains(q) || e.getSource().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized void remove(HistoryEntry entry) {
        if (entries.remove(entry)) {
            scheduleSave();
        }
    }

    public synchronized void clear() {
        entries.clear();
        scheduleSave();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Waits until everything is written to disk. */
    public void flush() {
        if (file == null) {
            return;
        }
        try {
            saver.submit(() -> {
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // nothing to do
        }
    }

    private void scheduleSave() {
        if (file == null) {
            return;
        }
        if (savePending.compareAndSet(false, true)) {
            saver.execute(() -> {
                savePending.set(false);
                writeNow(snapshot());
            });
        }
    }

    private void writeNow(List<HistoryEntry> copy) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeObject(new ArrayList<>(copy));
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotPossible) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save history: " + e);
        }
    }
}
