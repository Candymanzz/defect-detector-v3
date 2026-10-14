package com.example.iml.orchestrator.integration.io.mvs;

import com.sun.jna.*;
import java.nio.file.*;
import java.util.*;

/** Explicit Windows SDK paths resolve dependencies beside the vendor DLL. */
public final class MvsNativeLibrary {
    private MvsNativeLibrary() { }
    public static NativeLibrary load(String name) {
        Path path = Path.of(name);
        if (Platform.isWindows() && Files.isRegularFile(path))
            return NativeLibrary.getInstance(path.toAbsolutePath().normalize().toString(), Map.of(Library.OPTION_OPEN_FLAGS, 0x1100));
        return NativeLibrary.getInstance(name);
    }
    public static String key(NativeLibrary library) {
        String key;
        try { key = library.getFile().getCanonicalPath(); }
        catch (java.io.IOException e) { key = library.getFile().getAbsolutePath(); }
        return Platform.isWindows() ? key.toLowerCase(Locale.ROOT) : key;
    }
}
