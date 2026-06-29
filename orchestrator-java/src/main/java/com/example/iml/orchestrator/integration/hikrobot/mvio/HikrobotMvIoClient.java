package com.example.iml.orchestrator.integration.hikrobot.mvio;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Files;
import java.nio.file.Path;

/** Сессия MvIOInterfaceBox: DI1/DI2/DI3 → работа / направление / триггер. */
public final class HikrobotMvIoClient implements AutoCloseable {

    private final MvIoInterfaceBoxLibrary library;
    private final int activeLevel;
    private final Object lock = new Object();
    private Pointer handle;
    private boolean closed;

    public HikrobotMvIoClient(String comPort, int activeLevel, String dllDirectory) {
        if (!isWindows()) {
            throw new IllegalStateException("MvIOInterfaceBox.dll is Windows-only");
        }
        this.activeLevel = activeLevel != 0 ? 1 : 0;
        this.library = loadLibrary(dllDirectory);
        openSession(comPort);
    }

    public boolean readPortActive(int port) {
        synchronized (lock) {
            ensureOpen();
            if (port < 1 || port > 32) {
                throw new IllegalArgumentException("DI port must be 1..32, got " + port);
            }
            ByteByReference level = new ByteByReference((byte) 0);
            int ret = library.MV_IO_GetInputLevel(handle, (byte) port, level);
            if (ret != 0) {
                throw new IllegalStateException(
                        String.format("MV_IO_GetInputLevel(port=%d) failed: 0x%08X", port, ret)
                );
            }
            return (level.getValue() & 0xFF) == activeLevel;
        }
    }

    private void openSession(String comPort) {
        synchronized (lock) {
            PointerByReference ref = new PointerByReference();
            int ret = library.MV_IO_CreateHandle(ref);
            if (ret != 0 || ref.getValue() == null) {
                throw new IllegalStateException("MV_IO_CreateHandle failed: 0x" + Integer.toHexString(ret));
            }
            handle = ref.getValue();
            try {
                MvIoSerial serial = new MvIoSerial(comPort);
                serial.write();
                ret = library.MV_IO_Open(handle, serial);
                if (ret != 0) {
                    throw new IllegalStateException(
                            "MV_IO_Open(" + comPort + ") failed: 0x" + Integer.toHexString(ret)
                    );
                }
            } catch (RuntimeException e) {
                destroyHandle();
                throw e;
            }
        }
    }

    private MvIoInterfaceBoxLibrary loadLibrary(String dllDirectory) {
        if (dllDirectory != null && !dllDirectory.isBlank()) {
            return MvIoInterfaceBoxLibrary.load(dllDirectory);
        }
        for (Path candidate : NativeLibraryLoader.defaultSearchPaths()) {
            Path dll = candidate.resolve("MvIOInterfaceBox.dll");
            if (Files.isRegularFile(dll)) {
                return MvIoInterfaceBoxLibrary.load(candidate.toString());
            }
        }
        return MvIoInterfaceBoxLibrary.load(null);
    }

    private void ensureOpen() {
        if (closed || handle == null) {
            throw new IllegalStateException("MvIO session is closed");
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (handle != null) {
                try {
                    library.MV_IO_Close(handle);
                } catch (Exception ignored) {
                }
                destroyHandle();
            }
        }
    }

    private void destroyHandle() {
        if (handle != null) {
            try {
                library.MV_IO_DestroyHandle(handle);
            } catch (Exception ignored) {
            }
            handle = null;
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
