package com.example.iml.orchestrator.integration.bootstrap.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Параметры синхронной съёмки линии ({@code integration.simultaneous_line_capture}).
 */
public record SimultaneousLineCaptureConfig(
        boolean enabled,
        long barrierWaitMs,
        long postTriggerSettleMs,
        long interWaitFrameMs,
        boolean parallelWaitFrame,
        boolean immediatePrefire,
        boolean hardwareLineTrigger,
        int transferWaitWaves,
        long transferWaveGapMs
) {

    public static SimultaneousLineCaptureConfig parse(
            Map<String, Object> integration,
            Map<String, Object> root
    ) {
        return new SimultaneousLineCaptureConfig(
                parseEnabled(integration),
                parseBarrierMs(integration),
                parsePostTriggerSettleMs(integration),
                parseInterWaitFrameMs(integration),
                parseParallelWaitFrame(integration),
                parseImmediatePrefire(integration),
                parseHardwareLineTrigger(integration, root),
                parseTransferWaitWaves(integration, root),
                parseTransferWaveGapMs(integration, root)
        );
    }

    public void logTopology(Logger log, Map<String, Object> root) {
        if (root == null) {
            return;
        }
        int switches = YamlScalars.toInt(root.get("gige_switch_count"), 0);
        int perSwitch = YamlScalars.toInt(root.get("gige_cameras_per_switch"), 0);
        int perLink = YamlScalars.toInt(root.get("gige_ftd_cameras_per_link"), 0);
        int bufferKb = YamlScalars.toInt(root.get("gige_switch_buffer_kb"), 0);
        String exposureMode = hardwareLineTrigger
                ? "DI3→Line0 (hardware, все камеры в один электрический момент)"
                : "software trigger_only (все камеры параллельно, ~10 мс разброс IPC)";
        log.info(
                "gige topology: switches={} cameras_per_switch={} ftd_per_link={} switch_buffer_kb={} — "
                        + "передача: {} волна(ы) wait_frame (≤96 КБ: 2 волны; >96 КБ: GevSCFTD + 1 волна); экспозиция: {}",
                switches > 0 ? switches : "?",
                perSwitch > 0 ? perSwitch : "?",
                perLink > 0 ? perLink : "?",
                bufferKb,
                bufferKb > 0 && bufferKb <= 96 && perLink == 2 ? "2" : "1",
                exposureMode
        );
        if (!hardwareLineTrigger && perLink > 0 && perLink != 2) {
            log.warn(
                    "gige_ftd_cameras_per_link={} — для 5×2 ожидается 2 (пары id 0+1, 2+3, … на коммутаторе)",
                    perLink
            );
        }
    }

    private static boolean parseEnabled(Map<String, Object> integration) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map == null) {
            return true;
        }
        return YamlScalars.toBool(map.get("enabled"), true);
    }

    private static long parseBarrierMs(Map<String, Object> integration) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map == null) {
            return 250L;
        }
        return Math.max(0L, YamlScalars.toLong(map.get("barrier_wait_ms"), 250L));
    }

    private static boolean parseParallelWaitFrame(Map<String, Object> integration) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map == null) {
            return true;
        }
        return YamlScalars.toBool(map.get("parallel_wait_frame"), true);
    }

    private static boolean parseImmediatePrefire(Map<String, Object> integration) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map == null) {
            return true;
        }
        return YamlScalars.toBool(map.get("immediate_prefire"), true);
    }

    private static long parsePostTriggerSettleMs(Map<String, Object> integration) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map == null) {
            return 0L;
        }
        return Math.max(0L, YamlScalars.toLong(map.get("post_trigger_settle_ms"), 0L));
    }

    private static long parseInterWaitFrameMs(Map<String, Object> integration) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map == null) {
            return 0L;
        }
        return Math.max(0L, YamlScalars.toLong(map.get("inter_wait_frame_ms"), 0L));
    }

    private static int parseTransferWaitWaves(Map<String, Object> integration, Map<String, Object> root) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map != null && map.containsKey("transfer_wait_waves")) {
            return Math.max(1, YamlScalars.toInt(map.get("transfer_wait_waves"), 1));
        }
        int bufferKb = YamlScalars.toInt(root != null ? root.get("gige_switch_buffer_kb") : null, 0);
        int perLink = YamlScalars.toInt(root != null ? root.get("gige_ftd_cameras_per_link") : null, 0);
        if (bufferKb > 0 && bufferKb <= 96 && perLink == 2) {
            return 2;
        }
        return 1;
    }

    private static long parseTransferWaveGapMs(Map<String, Object> integration, Map<String, Object> root) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map != null && map.containsKey("transfer_wave_gap_ms")) {
            return Math.max(0L, YamlScalars.toLong(map.get("transfer_wave_gap_ms"), 0L));
        }
        int bufferKb = YamlScalars.toInt(root != null ? root.get("gige_switch_buffer_kb") : null, 0);
        if (bufferKb > 256) {
            return 15L;
        }
        if (bufferKb > 96) {
            return 80L;
        }
        return 220L;
    }

    private static boolean parseHardwareLineTrigger(Map<String, Object> integration, Map<String, Object> root) {
        Map<?, ?> map = lineCaptureMap(integration);
        if (map != null && map.containsKey("hardware_line_trigger")) {
            return YamlScalars.toBool(map.get("hardware_line_trigger"), false);
        }
        if (root == null) {
            return false;
        }
        String mode = String.valueOf(root.getOrDefault("capture_trigger_mode", "software")).trim().toLowerCase();
        return mode.equals("line0") || mode.equals("line1") || mode.equals("line") || mode.equals("hardware");
    }

    private static Map<?, ?> lineCaptureMap(Map<String, Object> integration) {
        if (integration == null) {
            return null;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return map;
    }
}
