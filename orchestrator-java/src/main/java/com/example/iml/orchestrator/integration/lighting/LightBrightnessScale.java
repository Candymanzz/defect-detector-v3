package com.example.iml.orchestrator.integration.lighting;

/**
 * Единая шкала яркости для оркестратора и будущего client API: {@code 0…100} (проценты).
 * COM IO (Scale255To100 в LightServer): 0…255. MV-LE по сети: 0…255.
 */
public final class LightBrightnessScale {

    private LightBrightnessScale() {
    }

    public static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public static int toMvLeBrightness(int unifiedPercent) {
        int p = clampPercent(unifiedPercent);
        return Math.max(0, Math.min(255, Math.round(p * 255f / 100f)));
    }

    public static int[] mvLeBrightnessForChannels(int unifiedPercent, int[] channels) {
        int b = toMvLeBrightness(unifiedPercent);
        int[] out = new int[channels.length];
        for (int i = 0; i < channels.length; i++) {
            out[i] = b;
        }
        return out;
    }

    /** Значение из YAML: 0…100 (%) или 0…255 (legacy) → процент для POST /api/com/light. */
    public static int toPercent(int value, int fallbackPercent) {
        if (value <= 100) {
            return clampPercent(value);
        }
        return clampPercent(Math.round(value * 100f / 255f));
    }
}
