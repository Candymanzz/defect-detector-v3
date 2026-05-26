package com.example.iml.orchestrator.integration.stream;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Подписчики {@code multipart/x-mixed-replace} (MJPEG) на кадры клиентского стрима.
 */
public final class MjpegStreamHub {

    private static final String BOUNDARY = "frame";
    private static final byte[] BOUNDARY_LINE = ("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PART_HEADER_PREFIX =
            "Content-Type: image/jpeg\r\nContent-Length: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PART_HEADER_SUFFIX = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PART_TRAILER = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Logger log;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public MjpegStreamHub(Logger log) {
        this.log = log;
    }

    public static String mjpegPath(int cameraId) {
        return "/api/camera/" + cameraId + "/stream.mjpeg";
    }

    public static String contentType() {
        return "multipart/x-mixed-replace; boundary=" + BOUNDARY;
    }

    /**
     * Блокирует вызывающий поток до закрытия соединения или {@link #closeAll()}.
     */
    public void serve(int cameraId, OutputStream out, Runnable onClosed) throws IOException {
        Subscriber sub = new Subscriber(cameraId, out);
        subscribers.add(sub);
        try {
            sub.runLoop();
        } finally {
            subscribers.remove(sub);
            sub.close();
            if (onClosed != null) {
                onClosed.run();
            }
        }
    }

    public void publish(int cameraId, byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            return;
        }
        for (Subscriber sub : subscribers) {
            if (sub.cameraId != cameraId || !sub.open) {
                continue;
            }
            sub.offer(jpegBytes);
        }
    }

    public void closeCamera(int cameraId) {
        for (Subscriber sub : subscribers) {
            if (sub.cameraId == cameraId) {
                sub.close();
            }
        }
    }

    public void closeAll() {
        for (Subscriber sub : subscribers) {
            sub.close();
        }
    }

    private final class Subscriber {
        private final int cameraId;
        private final OutputStream out;
        private final Object lock = new Object();
        private volatile boolean open = true;
        private volatile byte[] pending;

        Subscriber(int cameraId, OutputStream out) {
            this.cameraId = cameraId;
            this.out = out;
        }

        void offer(byte[] jpegBytes) {
            synchronized (lock) {
                if (!open) {
                    return;
                }
                pending = jpegBytes;
                lock.notifyAll();
            }
        }

        void close() {
            synchronized (lock) {
                open = false;
                lock.notifyAll();
            }
        }

        void runLoop() throws IOException {
            while (open) {
                byte[] frame;
                synchronized (lock) {
                    while (pending == null && open) {
                        try {
                            lock.wait(500L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            open = false;
                            return;
                        }
                    }
                    if (!open) {
                        return;
                    }
                    frame = pending;
                    pending = null;
                }
                if (frame != null) {
                    writePart(frame);
                }
            }
        }

        private void writePart(byte[] jpeg) throws IOException {
            synchronized (out) {
                out.write(BOUNDARY_LINE);
                out.write(PART_HEADER_PREFIX);
                out.write(Integer.toString(jpeg.length).getBytes(StandardCharsets.US_ASCII));
                out.write(PART_HEADER_SUFFIX);
                out.write(jpeg);
                out.write(PART_TRAILER);
                out.flush();
            }
        }
    }
}
