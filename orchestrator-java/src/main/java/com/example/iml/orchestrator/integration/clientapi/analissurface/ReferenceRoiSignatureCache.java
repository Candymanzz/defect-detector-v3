package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared reference/ROI signature cache for the whole HTTP pool (round-robin must not re-upload).
 */
public final class ReferenceRoiSignatureCache {

    private static final ConcurrentHashMap<String, String> SHARED_REFERENCE_SIGNATURES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> SHARED_ROI_SIGNATURES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> SCOPE_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    public ReferenceRoiSignatureCache(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void clearAll() {
        SHARED_REFERENCE_SIGNATURES.clear();
        SHARED_ROI_SIGNATURES.clear();
    }

    public String getReferenceSignature(String cacheKey) {
        return SHARED_REFERENCE_SIGNATURES.get(cacheKey);
    }

    public void putReferenceSignature(String cacheKey, String signature) {
        SHARED_REFERENCE_SIGNATURES.put(cacheKey, signature);
    }

    public String getRoiSignature(String roiKey) {
        return SHARED_ROI_SIGNATURES.get(roiKey);
    }

    public void putRoiSignature(String roiKey, String signature) {
        SHARED_ROI_SIGNATURES.put(roiKey, signature);
    }

    public Object scopeLock(String key) {
        return SCOPE_LOCKS.computeIfAbsent(key, ignored -> new Object());
    }

    public static String runtimeKey(String productType, int cameraId) {
        String normalizedProductType = checkProductType(productType);
        if (normalizedProductType.contains("#cam=")) {
            return normalizedProductType;
        }
        return normalizedProductType + "#cam=" + cameraId;
    }

    private static String checkProductType(String productType) {
        if (productType.isEmpty()) {
            return productType.trim();
        }
        else {
            return "";
        }
    }
    public static String scopedProductType(String productType, int cameraId) {
//        String normalized = productType == null ? "" : productType.trim();
        String normalized = checkProductType(productType);
        if (normalized.isEmpty() || cameraId < 0) {
            return normalized;
        }
        String suffix = "#cam=" + cameraId;
        if (normalized.endsWith(suffix)) {
            return normalized;
        }
        return normalized + suffix;
    }

    public static String referenceSignature(
            String shmName,
            Object shmOffset,
            Object width,
            Object height,
            Object stride,
            String contentFingerprint
    ) {
        return String.join(
                "|",
                shmName == null ? "" : shmName.trim(),
                String.valueOf(shmOffset),
                String.valueOf(width),
                String.valueOf(height),
                String.valueOf(stride),
                contentFingerprint == null ? "" : contentFingerprint
        );
    }

    /**
     * Без fingerprint перезапись эталона в тот же SHM (те же width/height) не триггерит
     * re-upload → Python сравнивает новый кадр со старым эталоном → anomaly=1.0.
     */
    public static String referenceContentFingerprint(Map<String, Object> header, String shmName, int cameraId) {
        Object explicit = header == null ? null : header.get("reference_content_fingerprint");
        if (explicit != null) {
            String value = String.valueOf(explicit).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        try {
            Path path = FrameJpegWriter.resolveShmPath(shmName, cameraId);
            if (path == null || !java.nio.file.Files.isRegularFile(path)) {
                return "missing";
            }
            return java.nio.file.Files.getLastModifiedTime(path).toMillis()
                    + ":"
                    + java.nio.file.Files.size(path);
        } catch (Exception e) {
            return "err";
        }
    }

    public String roiSignature(List<Map<String, Object>> points) {
        try {
            return mapper.writeValueAsString(points);
        } catch (Exception e) {
            return String.valueOf(points);
        }
    }

    public void rememberReferenceSignature(Map<String, Object> header) {
        String productType = String.valueOf(header.get("product_type"));
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        String shmName = String.valueOf(header.getOrDefault("shm_name", ""));
        if (productType.isBlank() || shmName.isBlank()) {
            return;
        }
        String signature = referenceSignature(
                shmName,
                header.get("shm_offset"),
                header.get("width"),
                header.get("height"),
                header.get("stride"),
                referenceContentFingerprint(header, shmName, cameraId)
        );
        putReferenceSignature(runtimeKey(productType, cameraId), signature);
    }
}
