package com.example.iml.geometry.shm;

import org.opencv.core.Mat;

import java.util.HashMap;
import java.util.Map;

/**
 * Кэш опорного кадра из отдельного SHM-сегмента (ключ — имя/смещение/размеры), как в прежнем GeometryRunnerMain.
 */
public final class ReferenceShmMatCache {

    private String cachedReferenceKey;
    private Mat cachedReferenceMat;

    public ReferenceMatResolution resolve(Map<String, Object> header, Mat current, ShmMatReader reader) {
        if (header.get("reference_shm_name") == null) {
            return new ReferenceMatResolution(current, false);
        }
        String key = buildReferenceKey(header);
        if (key.equals(cachedReferenceKey) && cachedReferenceMat != null) {
            return new ReferenceMatResolution(cachedReferenceMat, false);
        }

        Map<String, Object> refHeader = new HashMap<>();
        refHeader.put("shm_name", header.get("reference_shm_name"));
        refHeader.put("shm_offset", header.get("reference_shm_offset"));
        refHeader.put("width", header.get("reference_width"));
        refHeader.put("height", header.get("reference_height"));
        refHeader.put("stride", header.get("reference_stride"));
        Mat loaded = reader.readShmMat(refHeader);
        if (cachedReferenceMat != null) {
            cachedReferenceMat.release();
        }
        cachedReferenceMat = loaded;
        cachedReferenceKey = key;
        return new ReferenceMatResolution(cachedReferenceMat, false);
    }

    private static String buildReferenceKey(Map<String, Object> h) {
        return str(h.get("reference_shm_name"))
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
