package com.example.iml.orchestrator.integration.hikrobot.mvio;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/** JNA-обёртка MvIOInterfaceBox.dll (Hikrobot vision controller DI). */
public interface MvIoInterfaceBoxLibrary extends Library {

    String LIBRARY_NAME = "MvIOInterfaceBox";

    int MV_IO_CreateHandle(PointerByReference handle);

    int MV_IO_DestroyHandle(Pointer handle);

    int MV_IO_Open(Pointer handle, MvIoSerial serial);

    void MV_IO_Close(Pointer handle);

    int MV_IO_GetInputLevel(Pointer handle, MvIoInputLevel inputLevel);

    static MvIoInterfaceBoxLibrary load(String dllDirectory) {
        if (dllDirectory != null && !dllDirectory.isBlank()) {
            NativeLibraryLoader.addSearchPath(dllDirectory.trim());
        }
        return Native.load(LIBRARY_NAME, MvIoInterfaceBoxLibrary.class);
    }
}
