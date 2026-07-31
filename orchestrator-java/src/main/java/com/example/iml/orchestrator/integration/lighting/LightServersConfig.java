package com.example.iml.orchestrator.integration.lighting;

import java.util.List;
import java.util.Map;

/**
 * Конфигурация подсветки через LightServer.v3: три типа URL — вкл, выкл, яркость по камерам.
 * YAML-парсинг — {@link LightServersConfigMapper}.
 */
public record LightServersConfig(
        boolean enabled,
        boolean failOnError,
        int timeoutMs,
        int settleDelayMs,
        int flashLeadMs,
        int brightnessPercent,
        int durationMs,
        /** Постоянная подсветка: включить при старте, не гасить после capture. */
        boolean holdMode,
        String baseUrl,
        String onUrl,
        String offUrl,
        String brightnessPairUrl,
        String brightnessSingleUrl,
        String statusUrl,
        List<CameraFlashSpec> cameras
) {

    public enum FlashMode {
        PAIR,
        SINGLE
    }

    public record CameraFlashSpec(
            int cameraId,
            FlashMode mode,
            int brightnessPercent,
            int leftPercent,
            int rightPercent
    ) {
        public String endpointId() {
            return "camera-" + cameraId;
        }

        public int cameraNumber() {
            return cameraId + 1;
        }

        public int power255() {
            return LightBrightnessScale.toMvLeBrightness(brightnessPercent);
        }

        public int leftPower255() {
            return LightBrightnessScale.toMvLeBrightness(leftPercent);
        }

        public int rightPower255() {
            return LightBrightnessScale.toMvLeBrightness(rightPercent);
        }
    }

    public static LightServersConfig fromRootYaml(Map<String, Object> root) {
        return LightServersConfigMapper.fromRootYaml(root);
    }

    public static LightServersConfig disabled() {
        return new LightServersConfig(
                false, false, 1500, 0, 0, 100, 180, false,
                "http://127.0.0.1:5080",
                LightServerV3Http.PATH_COM_LIGHT,
                LightServerV3Http.PATH_COM_LIGHT,
                LightServerV3Http.PATH_CAMERA_FLASH_PAIR,
                LightServerV3Http.PATH_CAMERA_FLASH_SINGLE,
                LightServerV3Http.PATH_COM_LIGHT,
                List.of()
        );
    }

    public CameraFlashSpec camera(int cameraId) {
        for (CameraFlashSpec spec : cameras) {
            if (spec.cameraId() == cameraId) {
                return spec;
            }
        }
        return null;
    }

    /** Базовый URL LightServer.v3. */
    public String upstreamBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:5080" : baseUrl;
    }

    /** {@code flash_lead_ms} из {@code light_servers} или legacy {@code light_server}. */
    public static int flashLeadMsFromRoot(Map<String, Object> root) {
        int v = LightServersConfigMapper.readFlashLead(root == null ? null : root.get("light_servers"));
        if (v > 0) {
            return v;
        }
        return LightServersConfigMapper.readFlashLead(root == null ? null : root.get("light_server"));
    }

    /**
     * Вспышки: id 0–9 (camera_number 1–10) — Ethernet MV-LE pair, кроме id 2 и 7 (COM single).
     * См. config/blocks/51-light-hardware.yaml.
     */
    static boolean hasFlashHardware(int cameraId) {
        return cameraId >= 0 && cameraId <= 9;
    }
}
