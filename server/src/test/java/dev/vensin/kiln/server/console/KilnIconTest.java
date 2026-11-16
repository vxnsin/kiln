package dev.vensin.kiln.server.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class KilnIconTest {

    @Test
    void isASixtyFourPixelPngWithTheFireInTheMiddle() throws Exception {
        byte[] png = KilnIcon.png();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        assertEquals(0xffffd700, image.getRGB(2 + 4 * 6 + 3, 2 + 5 * 6 + 3), "yellow core of the fire");
        assertEquals(0, image.getRGB(0, 0) >>> 24, "corners stay transparent");
    }
}
