package com.example.iml.orchestrator.integration.clientapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable JPEG pin for UI settings TEST: one active frame per camera for the TEST session.
 * Survives rolling frame-archive overwrite/trim while the operator tunes knobs.
 */
public final class TestFramePinStore {

    public record Pin(
            int cameraId,
            long frameId,
            Path jpegPath,
            String previewHttpPath
    ) {
    }

    private final Path root;
    private final ConcurrentHashMap<Integer, Pin> byCamera = new ConcurrentHashMap<>();

    public TestFramePinStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public static TestFramePinStore openDefault() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "iml-test-pins");
        Files.createDirectories(root);
        return new TestFramePinStore(root);
    }

    public synchronized Pin pin(int cameraId, long frameId, byte[] jpegBytes, String previewHttpPath)
            throws IOException {
        if (cameraId < 0) {
            throw new IllegalArgumentException("cameraId required");
        }
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IllegalArgumentException("jpeg bytes required");
        }
        Files.createDirectories(root);
        Path cameraDir = root.resolve("camera_" + cameraId);
        Files.createDirectories(cameraDir);
        Path jpeg = cameraDir.resolve("frame.jpg");
        Path tmp = cameraDir.resolve("frame.jpg.tmp");
        Files.write(tmp, jpegBytes);
        try {
            Files.move(tmp, jpeg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, jpeg, StandardCopyOption.REPLACE_EXISTING);
        }
        Pin pin = new Pin(
                cameraId,
                frameId,
                jpeg,
                previewHttpPath == null || previewHttpPath.isBlank() ? null : previewHttpPath.trim()
        );
        byCamera.put(cameraId, pin);
        return pin;
    }

    public Optional<Pin> get(int cameraId) {
        Pin pin = byCamera.get(cameraId);
        if (pin == null || !Files.isRegularFile(pin.jpegPath())) {
            return Optional.empty();
        }
        return Optional.of(pin);
    }

    public synchronized void clear(int cameraId) {
        Pin removed = byCamera.remove(cameraId);
        if (removed != null) {
            deleteQuietly(removed.jpegPath());
            deleteQuietly(removed.jpegPath().getParent());
        }
    }

    public synchronized void clearAll() {
        for (Integer cameraId : byCamera.keySet().toArray(Integer[]::new)) {
            clear(cameraId);
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
