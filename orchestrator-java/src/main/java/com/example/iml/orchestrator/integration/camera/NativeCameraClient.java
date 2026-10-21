package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.integration.io.mvs.MvsSdkLease;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One camera owned by the JVM. Frames stay in the existing shared-memory ring. */
public final class NativeCameraClient implements BinaryClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Guarded by ENUMERATION_LOCK, also shared with IO and light enumeration.
    private static final Set<Integer> OPEN_CAMERAS = new HashSet<>();
    private final int cameraId;
    private final ReentrantLock calls = new ReentrantLock();
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final MvsSdkLease sdkLease;
    private volatile long handle;

    public NativeCameraClient(int cameraId, Path library, Path config) throws IOException {
        this.cameraId = cameraId;
        NativeCameraBindings.load(library);
        MvsSdkLease lease = null;
        synchronized (MvsSdkLease.ENUMERATION_LOCK) {
            if (OPEN_CAMERAS.contains(cameraId)) throw new IOException("Camera already open in this JVM: " + cameraId);
            try {
                String sdk = NativeCameraBindings.sdkLibrary();
                if (sdk != null) {
                    String key = Path.of(sdk).toFile().getCanonicalPath();
                    if (Platform.isWindows()) key = key.toLowerCase(Locale.ROOT);
                    lease = MvsSdkLease.acquire(key, () -> {
                        try { NativeCameraBindings.initializeSdk(); }
                        catch (IOException error) { throw new UncheckedIOException(error); }
                    }, NativeCameraBindings::finalizeSdk);
                }
                handle = NativeCameraBindings.open(config.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8), cameraId);
                if (handle == 0) throw new IOException("Camera JNI returned an empty handle: " + cameraId);
                OPEN_CAMERAS.add(cameraId);
            } catch (IOException | RuntimeException | LinkageError error) {
                if (lease != null) lease.close();
                throw new IOException("Cannot open native camera " + cameraId + ": " + error.getMessage(), error);
            }
        }
        sdkLease = lease;
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        return command(header, null);
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException {
        if (payload != null && payload.length != 0) throw new IOException("Camera commands do not accept image payloads");
        boolean callLocked = false;
        boolean readLocked = false;
        try {
            calls.lockInterruptibly();
            callLocked = true;
            lifetime.readLock().lockInterruptibly();
            readLocked = true;
            if (!isAlive()) throw new IOException("Native camera is closed: " + cameraId);
            byte[] reply = NativeCameraBindings.command(handle, JSON.writeValueAsBytes(header));
            if (reply == null || reply.length < 2) throw new IOException("Empty JNI camera response");
            int type = reply[0] & 0xff;
            if (type != BinaryProtocol.MSG_RESPONSE && type != BinaryProtocol.MSG_ERROR)
                throw new IOException("Unexpected JNI camera response type: " + type);
            Map<String, Object> response = JSON.readValue(reply, 1, reply.length - 1, new TypeReference<>() { });
            if ("stop".equals(header.get("op"))) requestStop();
            return new BinaryProtocol.Message(type, response, new byte[0]);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Native camera command interrupted: " + cameraId, error);
        } finally {
            if (readLocked) lifetime.readLock().unlock();
            if (callLocked) calls.unlock();
        }
    }

    @Override
    public boolean isAlive() {
        return handle != 0 && !closing.get();
    }

    @Override
    public void requestStop() {
        // Concurrent read locks let cancellation reach an in-flight SDK wait.
        lifetime.readLock().lock();
        try {
            if (closing.compareAndSet(false, true) && handle != 0) NativeCameraBindings.cancel(handle);
        } finally {
            lifetime.readLock().unlock();
        }
    }

    @Override
    public void close() {
        requestStop();
        lifetime.writeLock().lock();
        try {
            if (handle == 0) return;
            try { NativeCameraBindings.close(handle); }
            finally {
                handle = 0;
                synchronized (MvsSdkLease.ENUMERATION_LOCK) {
                    OPEN_CAMERAS.remove(cameraId);
                    if (sdkLease != null) sdkLease.close();
                }
            }
        } finally {
            lifetime.writeLock().unlock();
        }
    }
}
