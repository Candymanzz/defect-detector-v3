package com.example.iml.orchestrator.integration.clientapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable JPEG pin for UI settings TEST: one active frame per camera for the TEST session.
 * Survives rolling frame-archive overwrite/trim while the operator tunes knobs.
 */
public final class TestFramePinStore {

    public record Pin(
            String pinId,
            int cameraId,
            long frameId,
            String jpegSha256,
            Path jpegPath,
            String previewHttpPath,
            long createdAtMs
    ) {
    }

    private final Path root;
    private final ConcurrentHashMap<String, Pin> byId = new ConcurrentHashMap<>();
    private static final long TTL_MS = Duration.ofMinutes(30).toMillis();

    public TestFramePinStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public static TestFramePinStore openDefault() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "iml-test-pins");
        Files.createDirectories(root);
        return new TestFramePinStore(root);
    }

    public synchronized Pin pin(int cameraId, long frameId, byte[] jpegBytes, String jpegSha256)
            throws IOException {
        cleanupExpired();
        if (cameraId < 0) {
            throw new IllegalArgumentException("cameraId required");
        }
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IllegalArgumentException("jpeg bytes required");
        }
        // One active pin per camera: selecting another frame overwrites the previous TEST JPEG.
        clearCamera(cameraId);
        Files.createDirectories(root);
        String pinId = UUID.randomUUID().toString().replace("-", "");
        Path pinDir = root.resolve(pinId).normalize();
        if (!pinDir.startsWith(root)) {
            throw new IOException("invalid pin path");
        }
        Files.createDirectories(pinDir);
        Path jpeg = pinDir.resolve("frame.jpg");
        Path tmp = pinDir.resolve("frame.jpg.tmp");
        Files.write(tmp, jpegBytes);
        try {
            Files.move(tmp, jpeg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, jpeg, StandardCopyOption.REPLACE_EXISTING);
        }
        Pin pin = new Pin(
                pinId,
                cameraId,
                frameId,
                jpegSha256,
                jpeg,
                "/api/client/inspection/test-pin/" + pinId + "/frame.jpg",
                System.currentTimeMillis()
        );
        byId.put(pinId, pin);
        return pin;
    }

    public synchronized void clearCamera(int cameraId) {
        for (Pin pin : byId.values().toArray(Pin[]::new)) {
            if (pin.cameraId() == cameraId) {
                clear(pin.pinId());
            }
        }
    }

    public Optional<Pin> get(String pinId) {
        cleanupExpired();
        Pin pin = pinId == null ? null : byId.get(pinId.trim());
        if (pin == null || !Files.isRegularFile(pin.jpegPath())) {
            return Optional.empty();
        }
        return Optional.of(pin);
    }

    public synchronized void clear(String pinId) {
        Pin removed = byId.remove(pinId);
        if (removed != null) {
            deleteQuietly(removed.jpegPath());
            deleteQuietly(removed.jpegPath().getParent());
        }
    }

    public synchronized void clearAll() {
        for (String pinId : byId.keySet().toArray(String[]::new)) {
            clear(pinId);
        }
    }

    private synchronized void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        for (Pin pin : byId.values().toArray(Pin[]::new)) {
            if (pin.createdAtMs() < cutoff) clear(pin.pinId());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
