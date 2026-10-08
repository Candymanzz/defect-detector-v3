package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import java.util.*;

/** Native IO configuration; timer routing and duration belong to the controller. */
public record MvsIoConfig(boolean enabled, String library, String port, String serial,
                          String inputMode, String backend,
                          int pollMs, int reconnectMs, Map<Integer, Input> inputs, Map<Integer, String> outputs,
                          int healthCheckMs, int outputPollMs, int shutdownTimeoutMs, int scanLogEvery,
                          boolean configureInputs, List<String> dependencies, IoRejectConfig reject) {
    public record Input(String line, boolean invert, String risingEvent, String fallingEvent, int port, String edge) {
        public Input(String line, boolean invert, String risingEvent, String fallingEvent) {
            this(line, invert, risingEvent, fallingEvent, 0, "both");
        }
    }

    public static MvsIoConfig parse(Map<String, Object> integration) {
        Map<?, ?> trigger = integration != null && integration.get("inspection_trigger") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> cfg = trigger.get("mvs_io") instanceof Map<?, ?> m ? m : Map.of();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String root = System.getenv().getOrDefault("MVS_ROOT", "/opt/MVS");
        String backend = text(cfg, "backend", "mv_camera");
        if (!Set.of("mv_camera", "mv_io").contains(backend))
            throw new IllegalArgumentException("mvs_io.backend: mv_camera or mv_io");
        Map<Integer, Input> inputs = new TreeMap<>();
        if (cfg.get("inputs") instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> {
                int di = Integer.parseInt(key.toString());
                if (di < 1 || di > 8 || !(value instanceof Map<?, ?> spec)) {
                    throw new IllegalArgumentException("mvs_io.inputs: expected DI 1..8 and a mapping");
                }
                inputs.put(di, new Input(text(spec, "line", "In" + di),
                        YamlScalars.toBool(spec.get("invert"), false),
                        text(spec, "rising_event", ""), text(spec, "falling_event", ""),
                        YamlScalars.toInt(spec.get("port"), di), text(spec, "edge", "both")));
            });
        }
        if (inputs.isEmpty()) {
            if (backend.equals("mv_io")) throw new IllegalArgumentException("mvs_io.inputs: select the DI ports to subscribe");
            for (int di = 1; di <= 4; di++) inputs.put(di, new Input("In" + di, false, "", "", di, "both"));
        }
        for (Input input : inputs.values()) {
            if (input.port() < 1 || input.port() > 8 || !Set.of("rising", "falling", "both").contains(input.edge()))
                throw new IllegalArgumentException("mvs_io.inputs: port must be 1..8, edge rising/falling/both");
        }
        Map<Integer, String> outputs = new TreeMap<>();
        if (cfg.get("outputs") instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> {
                int port = Integer.parseInt(key.toString());
                if (port < 1 || port > 16 || value == null || value.toString().isBlank())
                    throw new IllegalArgumentException("mvs_io.outputs: expected DO 1..16 and a line name");
                outputs.put(port, value.toString().trim());
            });
        } else if (backend.equals("mv_camera")) outputs.put(5, "Out5");
        String mode = text(cfg, "input_mode", backend.equals("mv_io") ? "events" : "poll");
        if (!Set.of("events", "poll").contains(mode)) throw new IllegalArgumentException("mvs_io.input_mode: events or poll");
        if (backend.equals("mv_io") && !mode.equals("events"))
            throw new IllegalArgumentException("mv_io backend requires input_mode: events");
        List<String> dependencies = cfg.get("dependencies") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : List.of();
        String platformSuffix = windows ? "_windows" : "_linux";
        String library = text(cfg, "library" + platformSuffix,
                text(cfg, "library", backend.equals("mv_camera")
                        ? (windows ? "MvCameraControl" : root + "/lib/64/libMvCameraControl.so") : ""));
        String port = text(cfg, "port" + platformSuffix, text(cfg, "port", ""));
        boolean enabled = YamlScalars.toBool(cfg.get("enabled"), false);
        if (enabled && backend.equals("mv_io") && (library.isEmpty() || port.isEmpty()))
            throw new IllegalArgumentException("Event IO requires mvs_io.library" + platformSuffix
                    + " and mvs_io.port" + platformSuffix + " (or library/port); install compatible IO SDK");
        IoRejectConfig reject = IoRejectConfig.parse(integration);
        if (reject.enabled() && (!enabled || !backend.equals(reject.mode().equals("timer") ? "mv_camera" : "mv_io")))
            throw new IllegalArgumentException("io_reject requires enabled mvs_io: timer mode uses mv_camera, level mode uses mv_io");
        return new MvsIoConfig(enabled, library, port, text(cfg, "serial", ""), mode, backend,
                positive(cfg, "poll_ms", 1),
                positive(cfg, "reconnect_ms", 1000), Map.copyOf(inputs), Map.copyOf(outputs),
                positive(cfg, "health_check_ms", 1000), positive(cfg, "output_poll_ms", 1000),
                positive(cfg, "shutdown_timeout_ms", 5000), positive(cfg, "scan_log_every", 1000),
                YamlScalars.toBool(cfg.get("configure_inputs"), true), dependencies, reject);
    }

    private static int positive(Map<?, ?> cfg, String key, int fallback) {
        int value = YamlScalars.toInt(cfg.get(key), fallback);
        if (value < 1) throw new IllegalArgumentException("mvs_io." + key + " must be positive");
        return value;
    }

    private static String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString().trim();
    }
}
