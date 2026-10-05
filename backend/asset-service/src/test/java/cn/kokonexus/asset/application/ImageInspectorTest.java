package cn.kokonexus.asset.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ImageInspectorTest {

    private final ImageInspector inspector = new ImageInspector();

    @Test
    void acceptsAndNormalizesPng() throws Exception {
        var image = inspector.inspect(file("avatar.png", "image/png", png(2, 3)));
        assertEquals("image/png", image.contentType());
        assertEquals("png", image.extension());
        assertEquals(2, image.width());
        assertEquals(3, image.height());
        assertEquals(64, image.sha256().length());
        assertTrue(image.bytes().length > 0);
    }

    @Test
    void rejectsForgedMimeAndExtension() throws Exception {
        byte[] data = png(2, 2);
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(file("avatar.png", "image/jpeg", data)));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(file("avatar.jpg", "image/png", data)));
    }

    @Test
    void rejectsFakeImageEvenWithMatchingHeaders() {
        byte[] forged = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0 };
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(file("avatar.png", "image/png", forged)));
    }

    @Test
    void rejectsOversizedDimensionsBeforeDecode() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(file("cover.png", "image/png", png(4097, 1)))
        );
    }

    @Test
    void rejectsPathLikeFilename() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(file("../avatar.png", "image/png", png(1, 1)))
        );
    }

    @Test
    void stopsEncodingBeforeTheOutputCacheCanExceedFiveMib() {
        BufferedImage image = new BufferedImage(1400, 1400, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(42);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
        }
        assertThrows(IOException.class, () -> inspector.encode(image, "png"));
    }

    private MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
