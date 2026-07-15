package com.example.iml.positioning.shm;

import org.opencv.core.Mat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReferenceShmMatCache {

    private static final int MAX_CACHED_REFERENCES = 2;

    private final Map<String, Mat> cache = new LinkedHashMap<>(MAX_CACHED_REFERENCES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Mat> eldest) {
            if (size() <= MAX_CACHED_REFERENCES) {
                return false;
            }
            Mat mat = eldest.getValue();
            if (mat != null) {
                mat.release();
            }
            return true;
        }
    };

    public ReferenceMatResolution resolve(Map<String, Object> header, Mat current, ShmMatReader reader) {
        if (header.get("reference_shm_name") == null) {
            return new ReferenceMatResolution(current, false);
        }
        String key = referenceKey(header);
        Mat cached = cache.get(key);
        if (cached != null) {
            return new ReferenceMatResolution(cached, false);
        }

        Map<String, Object> refHeader = new HashMap<>();
        refHeader.put("shm_name", header.get("reference_shm_name"));
        refHeader.put("shm_offset", header.get("reference_shm_offset"));
        refHeader.put("width", header.get("reference_width"));
        refHeader.put("height", header.get("reference_height"));
        refHeader.put("stride", header.get("reference_stride"));
        Mat loaded = reader.readShmMat(refHeader);
        Mat previous = cache.put(key, loaded);
        if (previous != null && previous != loaded) {
            previous.release();
        }
        return new ReferenceMatResolution(loaded, false);
    }

    public static String referenceKey(Map<String, Object> h) {
        return str(h.get("camera_id"))
                + "|" + str(h.get("reference_shm_name"))
                + "|" + (int) num(h.get("reference_shm_offset"), 0)
                + "|" + (int) num(h.get("reference_width"), 0)
                + "|" + (int) num(h.get("reference_height"), 0)
                + "|" + (int) num(h.get("reference_stride"), 0);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return fallback;
        }
        return Double.parseDouble(String.valueOf(o));
    }

    public record ReferenceMatResolution(Mat mat, boolean releaseAfterUse) {
    }
}
