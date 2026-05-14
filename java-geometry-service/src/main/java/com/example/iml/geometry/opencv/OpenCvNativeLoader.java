package com.example.iml.geometry.opencv;

public final class OpenCvNativeLoader {

    private static volatile boolean loaded;

    private OpenCvNativeLoader() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        nu.pattern.OpenCV.loadLocally();
        loaded = true;
    }
}
