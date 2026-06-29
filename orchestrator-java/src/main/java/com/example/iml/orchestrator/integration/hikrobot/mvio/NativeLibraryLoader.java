package com.example.iml.orchestrator.integration.hikrobot.mvio;

import com.sun.jna.NativeLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class NativeLibraryLoader {

    private NativeLibraryLoader() {
    }

    static void addSearchPath(String directory) {
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("MvIO DLL directory not found: " + dir);
        }
        NativeLibrary.addSearchPath(MvIoInterfaceBoxLibrary.LIBRARY_NAME, dir.toAbsolutePath().toString());
    }

    static List<Path> defaultSearchPaths() {
        List<Path> paths = new ArrayList<>();
        paths.add(Path.of("native", "win64"));
        paths.add(Path.of("orchestrator-java", "native", "win64"));
        paths.add(Path.of("C:\\Program Files (x86)\\MV_VC_VB_VT_IO_SDK_V2.0.0.3_Repair_Build240620\\Demo\\C#\\win64"));
        paths.add(Path.of("C:\\Program Files (x86)\\MVS\\Development\\MV_VC_VB_VT_IO_SDK\\Runtime\\win64"));
        return paths;
    }
}
