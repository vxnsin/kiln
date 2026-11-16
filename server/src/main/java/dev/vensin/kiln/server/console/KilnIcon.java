package dev.vensin.kiln.server.console;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** The kiln pixel art, shared by the console header and the default server list icon. */
public final class KilnIcon {

    /** Ten by ten pixels; each letter is a color from {@link #PALETTE}, anything else is empty. */
    public static final String[] PIXELS = {
        "...bbbb...",
        "..bBbbBb..",
        ".bbbbbbbb.",
        ".bBbddbBb.",
        ".bbdoodbb.",
        ".bbdyydbb.",
        ".bBdyydBb.",
        ".bbdoodbb.",
        "gggggggggg",
        ".gggggggg.",
    };
    public static final String KEYS = "bBdoyg";
    /** The same colors as 256-color terminal codes and as RGB. */
    public static final int[] TERMINAL = {173, 131, 52, 208, 220, 242};
    private static final int[] RGB = {0xd7875f, 0xaf5f5f, 0x5f0000, 0xff8700, 0xffd700, 0x6c6c6c};

    private KilnIcon() {}

    /** A 64 by 64 PNG for the server list: six screen pixels per art pixel and a two-pixel border. */
    public static byte[] png() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < PIXELS.length; y++) {
            for (int x = 0; x < PIXELS[y].length(); x++) {
                int key = KEYS.indexOf(PIXELS[y].charAt(x));
                if (key < 0) {
                    continue;
                }
                for (int dy = 0; dy < 6; dy++) {
                    for (int dx = 0; dx < 6; dx++) {
                        image.setRGB(2 + x * 6 + dx, 2 + y * 6 + dy, 0xff000000 | RGB[key]);
                    }
                }
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
