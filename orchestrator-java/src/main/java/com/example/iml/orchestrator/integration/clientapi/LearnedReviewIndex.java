package com.example.iml.orchestrator.integration.clientapi;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Сессионная карта Python {@code inspection_id} (UUID review) по кадру.
 * Нужна, чтобы фронт мог слать {@code frameId + productType} без UUID из WS.
 */
public final class LearnedReviewIndex {

    private static final ConcurrentHashMap<String, String> BY_CAMERA_FRAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> BY_PRODUCT_FRAME = new ConcurrentHashMap<>();

    private LearnedReviewIndex() {
    }

    public static void remember(int cameraId, long frameId, String scopedProductType, String reviewId) {
        String id = normalize(reviewId);
        if (id == null || frameId < 0) {
            return;
        }
        if (cameraId >= 0) {
            BY_CAMERA_FRAME.put(cameraKey(cameraId, frameId), id);
        }
        String product = scopedProductType == null ? "" : scopedProductType.trim();
        if (!product.isEmpty()) {
            BY_PRODUCT_FRAME.put(productKey(product, frameId), id);
        }
    }

    public static String lookup(Integer cameraId, Long frameId, String productType) {
        if (frameId == null || frameId < 0) {
            return null;
        }
        if (cameraId != null && cameraId >= 0) {
            String byCamera = BY_CAMERA_FRAME.get(cameraKey(cameraId, frameId));
            if (byCamera != null) {
                return byCamera;
            }
        }
        String product = productType == null ? "" : productType.trim();
        if (!product.isEmpty()) {
            return BY_PRODUCT_FRAME.get(productKey(product, frameId));
        }
        return null;
    }

    public static String scopedProductType(String productType, Integer cameraId) {
        String normalized = productType == null ? "" : productType.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        if (normalized.contains("#cam=")) {
            return normalized;
        }
        if (cameraId == null || cameraId < 0) {
            return normalized;
        }
        return normalized + "#cam=" + cameraId;
    }

    private static String cameraKey(int cameraId, long frameId) {
        return cameraId + ":" + frameId;
    }

    private static String productKey(String productType, long frameId) {
        return productType + ":" + frameId;
    }

    private static String normalize(String reviewId) {
        if (reviewId == null) {
            return null;
        }
        String trimmed = reviewId.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }
}
