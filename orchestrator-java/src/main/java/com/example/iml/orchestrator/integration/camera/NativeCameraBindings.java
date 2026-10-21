package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.integration.io.mvs.MvsNativeLibrary;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** JNI entry points; no child process or binary protocol transport. */
final class NativeCameraBindings {
    private static Path loadedLibrary;
    // Retain the vendor DLL and resolve its dependencies beside it on Windows.
    private static NativeLibrary vendorRuntime;

    private NativeCameraBindings() { }

    static synchronized void load(Path library) throws IOException {
        Path resolved = library.toRealPath();
        if (loadedLibrary != null) {
            if (!loadedLibrary.equals(resolved)) throw new IOException("Camera JNI already loaded from " + loadedLibrary);
            return;
        }
        try {
            Path vendorDll = resolved.resolveSibling("MvCameraControl.dll");
            if (Platform.isWindows() && Files.isRegularFile(vendorDll)) {
                vendorRuntime = MvsNativeLibrary.load(vendorDll.toString());
            }
            System.load(resolved.toString());
            loadedLibrary = resolved;
        } catch (LinkageError | RuntimeException error) {
            throw new IOException("Cannot load camera JNI library " + resolved + ": " + error.getMessage(), error);
        }
    }

    static native String sdkLibrary() throws IOException;
    static native void initializeSdk() throws IOException;
    static native void finalizeSdk();
    static native long open(byte[] configPath, int cameraId) throws IOException;
    static native byte[] command(long handle, byte[] headerJson) throws IOException;
    static native void cancel(long handle);
    static native void close(long handle);
}
