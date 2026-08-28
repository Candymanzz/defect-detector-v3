package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class JpegBgrShmWriterTest {

    @Test
    void ensureJpegSizeReturnsOriginalWhenDimensionsMatch() throws IOException {
        byte[] jpeg = encodeJpeg(32, 24);
        byte[] out = JpegBgrShmWriter.ensureJpegSize(jpeg, 32, 24);
        assertArrayEquals(jpeg, out);
    }

    @Test
    void ensureJpegSizeResizesToReferenceDimensions() throws IOException {
        byte[] jpeg = encodeJpeg(16, 12);
        byte[] out = JpegBgrShmWriter.ensureJpegSize(jpeg, 32, 24);
        assertNotSame(jpeg, out);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(32, decoded.getWidth());
        assertEquals(24, decoded.getHeight());
    }

    private static byte[] encodeJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
