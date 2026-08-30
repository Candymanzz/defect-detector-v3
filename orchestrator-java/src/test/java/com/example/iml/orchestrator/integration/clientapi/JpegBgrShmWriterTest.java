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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void jpegDimensionsReadsEncodedSize() throws IOException {
        byte[] jpeg = encodeJpeg(40, 30);
        int[] dims = JpegBgrShmWriter.jpegDimensions(jpeg);
        assertEquals(40, dims[0]);
        assertEquals(30, dims[1]);
    }

    @Test
    void ensureJpegSizeRejectsInvalidTarget() {
        assertThrows(IOException.class, () -> JpegBgrShmWriter.ensureJpegSize(encodeJpeg(8, 6), 0, 10));
    }

    private static byte[] encodeJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
