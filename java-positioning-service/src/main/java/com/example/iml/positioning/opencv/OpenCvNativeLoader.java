package com.example.iml.positioning.opencv;

import org.opencv.core.Core;

public final class OpenCvNativeLoader {

    private static volatile boolean loaded;

    private OpenCvNativeLoader() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        nu.pattern.OpenCV.loadLocally();
        // Parallelism comes from the process pool — keep one OpenCV worker thread
        // per JVM so 10 workers do not each spawn a full intra-op thread pool.
        try {
            Core.setNumThreads(1);
        } catch (Throwable ignored) {
            // older bindings may not expose setNumThreads
        }
        loaded = true;
    }
}
