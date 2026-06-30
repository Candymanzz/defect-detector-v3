package com.example.iml.orchestrator.integration.hikrobot.mvio;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Files;
import java.nio.file.Path;

/** Сессия MvIOInterfaceBox: DI1/DI2/DI3 → работа / направление / триггер. */
public final class HikrobotMvIoClient implements AutoCloseable {

    private static final byte READ_ALL_PORTS_MASK = (byte) 0xFF;

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

    /** Активен ли вход port (1..8) относительно {@code active_value} из конфига. */
    public boolean readPortActive(int port) {
        synchronized (lock) {
            MvIoInputLevel input = fetchInputLevels();
            return isActive(input.levelForPort(port));
        }
    }

    /** Уровни DI1..DI8 за один вызов SDK. */
    public boolean[] readInputLevels() {
        synchronized (lock) {
            MvIoInputLevel input = fetchInputLevels();
            boolean[] levels = new boolean[8];
            for (int port = 1; port <= 8; port++) {
                levels[port - 1] = isActive(input.levelForPort(port));
            }
            return levels;
        }
    }

    private MvIoInputLevel fetchInputLevels() {
        ensureOpen();
        MvIoInputLevel input = new MvIoInputLevel();
        input.nPortNumber = READ_ALL_PORTS_MASK;
        input.write();
        int ret = library.MV_IO_GetInputLevel(handle, input);
        if (ret != 0) {
            throw new IllegalStateException("MV_IO_GetInputLevel failed: 0x" + Integer.toHexString(ret));
        }
        input.read();
        return input;
    }

    private boolean isActive(byte rawLevel) {
        return (rawLevel & 0xFF) == activeLevel;
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
