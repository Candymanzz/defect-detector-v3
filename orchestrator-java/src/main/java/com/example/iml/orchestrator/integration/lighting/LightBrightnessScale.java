package com.example.iml.orchestrator.integration.lighting;

/**
 * Единая шкала яркости для оркестратора и будущего client API: {@code 0…100} (проценты).
 * LightServer (COM): 0…100. LightServerv.v2 (MV-LE): 0…255.
 */
public final class LightBrightnessScale {

    private LightBrightnessScale() {
    }

    public static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public static int toComControllerPercent(int unifiedPercent) {
        return clampPercent(unifiedPercent);
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
}
