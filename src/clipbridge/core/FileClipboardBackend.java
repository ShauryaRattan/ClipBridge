package clipbridge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * A clipboard stored in a text file. Used for tests and for running two copies of the app on one
 * computer (two copies would otherwise share the one real clipboard).
 *
 * Windows refuses to read or replace a file for a moment while another thread is replacing it,
 * so both directions retry a few times.
 */
public final class FileClipboardBackend implements ClipboardBackend {
    private static final int RETRIES = 80;
    private static final long RETRY_PAUSE_MS = 15;

    private final Path path;
    private final Object writeLock = new Object();

    public FileClipboardBackend(Path path) {
        this.path = path.toAbsolutePath();
    }

    @Override
    public String get() {
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                if (!Files.exists(path)) {
                    return null;
                }
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } catch (NoSuchFileException gone) {
                return null;
            } catch (IOException locked) {
                pause();
            }
        }
        return null;
    }

    @Override
    public void set(String text) {
        synchronized (writeLock) {
            try {
                Path dir = path.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                Path tmp = path.resolveSibling(path.getFileName() + "." + System.nanoTime() + ".tmp");
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
                for (int attempt = 0; attempt < RETRIES; attempt++) {
                    try {
                        moveOver(tmp);
                        return;
                    } catch (IOException locked) {
                        pause();
                    }
                }
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot write the clipboard file " + path + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Replaces the file in one step, so a reader never sees it missing or half written.
     * (A plain REPLACE_EXISTING move deletes the target first on Linux and macOS.)
     */
    private void moveOver(Path tmp) throws IOException {
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notSupported) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
