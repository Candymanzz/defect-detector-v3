package com.example.iml.orchestrator.integration.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MjpegStreamHubTest {

    @Test
    void staticHelpersExposeExpectedValues() {
        assertEquals("/api/camera/7/stream.mjpeg", MjpegStreamHub.mjpegPath(7));
        assertEquals("multipart/x-mixed-replace; boundary=frame", MjpegStreamHub.contentType());
    }

    @Test
    void publishDeliversMultipartFrameToSubscriber() throws Exception {
        MjpegStreamHub hub = new MjpegStreamHub();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CountDownLatch frameReceived = new CountDownLatch(1);
        AtomicReference<byte[]> captured = new AtomicReference<>();

        Thread subscriber = new Thread(() -> {
            try {
                hub.serve(1, out, new byte[]{9, 8}, () -> {});
            } catch (Exception ignored) {
            }
        });
        subscriber.start();

        Thread.sleep(50);
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02};
        hub.publish(1, jpeg);
        frameReceived.await(2, TimeUnit.SECONDS);
        hub.closeAll();
        subscriber.join(2000);

        byte[] written = out.toByteArray();
        String text = new String(written, StandardCharsets.US_ASCII);
        assertTrue(text.contains("--frame"));
        assertTrue(text.contains("Content-Type: image/jpeg"));
        assertTrue(text.contains("Content-Length: " + jpeg.length));
        captured.set(written);
        assertTrue(captured.get().length > jpeg.length);
    }

    @Test
    void publishIgnoresEmptyPayloadAndOtherCameras() throws Exception {
        MjpegStreamHub hub = new MjpegStreamHub();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Thread subscriber = new Thread(() -> {
            try {
                hub.serve(2, out, null, () -> {});
            } catch (Exception ignored) {
            }
        });
        subscriber.start();
        Thread.sleep(30);

        hub.publish(2, new byte[0]);
        hub.publish(3, new byte[]{1});
        hub.publish(2, new byte[]{5, 6});
        Thread.sleep(100);
        hub.closeCamera(2);
        subscriber.join(2000);

        String text = new String(out.toByteArray(), StandardCharsets.US_ASCII);
        assertTrue(text.contains("Content-Length: 2"));
        assertEquals(1, text.split("Content-Length: 2").length - 1);
    }
}
