package com.example.iml.orchestrator.integration.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Quiet filesystem helpers for SHM janitor. */
final class ImlShmFileOps {

    private ImlShmFileOps() {
    }

    static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    static long fileTimeMs(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return System.currentTimeMillis();
        }
    }

    static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ignored) {
            return false;
        }
    }
}
