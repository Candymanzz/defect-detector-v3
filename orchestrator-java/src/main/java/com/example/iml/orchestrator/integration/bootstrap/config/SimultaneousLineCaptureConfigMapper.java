package com.example.iml.orchestrator.integration.bootstrap.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * YAML → {@link SimultaneousLineCaptureConfig} и диагностика топологии GigE.
 */
public final class  SimultaneousLineCaptureConfigMapper {

    private SimultaneousLineCaptureConfigMapper() {
    }

    public static SimultaneousLineCaptureConfig fromYaml(
            Map<String, Object> integration,
            Map<String, Object> root
    ) {
        Map<?, ?> cfg = Optional.ofNullable(integration).orElseGet(Map::of);
        Map<?, ?> rootCfg = Optional.ofNullable(root).orElseGet(Map::of);
        Object rawSection = cfg.get("simultaneous_line_capture");
        Map<?, ?> section = rawSection instanceof Map<?, ?> map ? map : Map.of();

        return new SimultaneousLineCaptureConfig(
                boolValue(section, "enabled", true),
                Math.max(0L, longValue(section, "barrier_wait_ms", 250L)),
                Math.max(0L, longValue(section, "post_trigger_settle_ms", 0L)),
                Math.max(0L, longValue(section, "inter_wait_frame_ms", 0L)),
                boolValue(section, "parallel_wait_frame", true),
                boolValue(section, "immediate_prefire", true),
                hardwareLineTrigger(section, rootCfg),
                transferWaitWaves(section, rootCfg),
                transferWaveGapMs(section, rootCfg)
        );
    }

    public static void logTopology(
            SimultaneousLineCaptureConfig cfg,
            Logger log,
            Map<String, Object> root
    ) {
        Map<?, ?> rootCfg = Optional.ofNullable(root).orElseGet(Map::of);
        int switches = intValue(rootCfg, "gige_switch_count", 0);
        int perSwitch = intValue(rootCfg, "gige_cameras_per_switch", 0);
        int perLink = intValue(rootCfg, "gige_ftd_cameras_per_link", 0);
        int bufferKb = intValue(rootCfg, "gige_switch_buffer_kb", 0);
        String exposureMode = cfg.hardwareLineTrigger()
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
        if (!cfg.hardwareLineTrigger() && perLink > 0 && perLink != 2) {
            log.warn(
                    "gige_ftd_cameras_per_link={} — для 5×2 ожидается 2 (пары id 0+1, 2+3, … на коммутаторе)",
                    perLink
            );
        }
    }

    private static boolean hardwareLineTrigger(Map<?, ?> section, Map<?, ?> root) {
        if (section.containsKey("hardware_line_trigger")) {
            return boolValue(section, "hardware_line_trigger", false);
        }
        Object modeRaw = root.get("capture_trigger_mode");
        String mode = String.valueOf(modeRaw != null ? modeRaw : "software").trim().toLowerCase();
        return mode.equals("line0") || mode.equals("line1") || mode.equals("line") || mode.equals("hardware");
    }

    private static int transferWaitWaves(Map<?, ?> section, Map<?, ?> root) {
        if (section.containsKey("transfer_wait_waves")) {
            return Math.max(1, intValue(section, "transfer_wait_waves", 1));
        }
        int bufferKb = intValue(root, "gige_switch_buffer_kb", 0);
        int perLink = intValue(root, "gige_ftd_cameras_per_link", 0);
        return bufferKb > 0 && bufferKb <= 96 && perLink == 2 ? 2 : 1;
    }

    private static long transferWaveGapMs(Map<?, ?> section, Map<?, ?> root) {
        if (section.containsKey("transfer_wave_gap_ms")) {
            return Math.max(0L, longValue(section, "transfer_wave_gap_ms", 0L));
        }
        int bufferKb = intValue(root, "gige_switch_buffer_kb", 0);
        if (bufferKb > 256) {
            return 15L;
        }
        if (bufferKb > 96) {
            return 80L;
        }
        return 220L;
    }

    private static int intValue(Map<?, ?> map, String key, int fallback) {
        return YamlScalars.toInt(map.get(key), fallback);
    }

    private static long longValue(Map<?, ?> map, String key, long fallback) {
        return YamlScalars.toLong(map.get(key), fallback);
    }

    private static boolean boolValue(Map<?, ?> map, String key, boolean fallback) {
        return YamlScalars.toBool(map.get(key), fallback);
    }
}
