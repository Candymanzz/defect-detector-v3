package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.config.YamlFileConfigLoader;
import org.apache.logging.log4j.LogManager;
import java.nio.file.Path;
import java.util.*;

/** Manual physical test: one TriggerSoftware pulse, never executed by Maven tests. */
public final class MvsTimerSmokeMain {
    public static void main(String[] args) throws Exception {
        Map<String, Object> options = new HashMap<>();
        Path configPath = null;
        String timer = "Timer6", line = "Out6";
        int group = 0;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
            String value = args[++i];
            switch (arg) {
                case "--config" -> configPath = Path.of(value);
                case "--group" -> group = Integer.parseInt(value);
                case "--timer" -> timer = value;
                case "--line" -> line = value;
                case "--port", "--serial", "--library" -> options.put(arg.substring(2), value);
                default -> throw new IllegalArgumentException("Usage: MvsTimerSmokeMain [--config FILE --group 0] or [--port SDK_NAME --library PATH --timer Timer6 --line Out6]");
            }
        }
        Map<String, Object> integration;
        if (configPath != null) {
            if (!options.isEmpty()) throw new IllegalArgumentException("Use --config or SDK overrides");
            Map<String, Object> root = new YamlFileConfigLoader().load(configPath);
            @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) root.getOrDefault("integration", Map.of());
            integration = parsed;
            IoRejectConfig reject = IoRejectConfig.parse(integration);
            if (!reject.mode().equals("timer")) throw new IllegalArgumentException("Config must use io_reject.mode: timer");
            IoRejectConfig.Output output = reject.groups().get(group);
            if (output == null) throw new IllegalArgumentException("Unknown reject group " + group);
            timer = output.timer(); line = output.line();
        } else {
            options.put("backend", "mv_camera"); options.put("input_mode", "poll");
            integration = Map.of("inspection_trigger", Map.of("mvs_io", options));
        }
        MvsIoConfig config = MvsIoConfig.parse(integration);
        if (!config.backend().equals("mv_camera")) throw new IllegalArgumentException("Timers require MvCameraControl SDK");
        try (JnaMvsIoDevice device = new JnaMvsIoDevice(config, LogManager.getLogger(MvsTimerSmokeMain.class))) {
            device.open(false);
            device.triggerTimer(timer, line);
            System.out.println("ONE pulse accepted: " + timer + " -> " + line + "; check the physical lamp. Duration/delay are configured in MVS.");
        }
    }
}
