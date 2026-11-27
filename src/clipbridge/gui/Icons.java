package clipbridge.gui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads the ClipBridge logo (PNG files stored inside the jar next to this class). */
public final class Icons {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> all;

    private Icons() {
    }

    /** All sizes, for Window.setIconImages: Windows picks the right one for the title bar, taskbar and Alt+Tab. */
    public static synchronized List<Image> windowIcons() {
        if (all == null) {
            all = new ArrayList<>();
            for (int size : SIZES) {
                BufferedImage img = read(size);
                if (img != null) {
                    all.add(img);
                }
            }
        }
        return all;
    }

    /** The logo closest to {@code size} pixels, scaled smoothly to exactly that size. */
    public static Image logo(int size) {
        List<Image> icons = windowIcons();
        if (icons.isEmpty()) {
            return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        }
        Image best = icons.get(icons.size() - 1);
        for (Image i : icons) {
            if (i.getWidth(null) >= size) {
                best = i;
                break;
            }
        }
        if (best.getWidth(null) == size) {
            return best;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(best, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static BufferedImage read(int size) {
        try (InputStream in = Icons.class.getResourceAsStream("logo-" + size + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null; // a missing icon must never stop the app from starting
        }
    }
}
