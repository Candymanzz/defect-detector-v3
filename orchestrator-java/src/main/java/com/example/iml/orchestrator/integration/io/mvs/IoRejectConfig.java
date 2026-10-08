package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import java.util.*;

public record IoRejectConfig(boolean enabled, int pulseMs, int ackTimeoutMs, int queueSize,
                             int shutdownTimeoutMs, Map<Integer, Output> groups, String mode) {
    public IoRejectConfig(boolean enabled, int pulseMs, int ackTimeoutMs, int queueSize, int shutdownTimeoutMs, Map<Integer, Output> groups) {
        this(enabled, pulseMs, ackTimeoutMs, queueSize, shutdownTimeoutMs, groups, "level");
    }
    public record Output(int port, int sdkPort, boolean activeHigh, boolean enableOnWrite, String timer, String line) {
        public Output(int port, int sdkPort, boolean activeHigh, boolean enableOnWrite) { this(port, sdkPort, activeHigh, enableOnWrite, "", "Out" + port); }
    }
    public static IoRejectConfig parse(Map<String, Object> integration) {
        Map<?, ?> cfg = integration != null && integration.get("io_reject") instanceof Map<?, ?> m ? m : Map.of();
        String mode = Objects.toString(cfg.get("mode"), "level");
        if (!Set.of("timer", "level").contains(mode)) throw new IllegalArgumentException("io_reject.mode: timer or level");
        Set<String> timers = new HashSet<>();
        boolean enabled = YamlScalars.toBool(cfg.get("enabled"), false);
        Set<Integer> protectedPorts = new HashSet<>();
        if (cfg.get("protected_outputs") instanceof List<?> ports) ports.forEach(p -> protectedPorts.add(Integer.parseInt(p.toString())));
        else protectedPorts.add(5);
        Map<Integer, Output> groups = new TreeMap<>(); Set<Integer> ports = new HashSet<>(), sdkPorts = new HashSet<>();
        if (cfg.get("groups") instanceof Map<?, ?> mapping) mapping.forEach((key, value) -> {
            int group = Integer.parseInt(key.toString());
            if (group < 0 || !(value instanceof Map<?, ?> spec)) throw new IllegalArgumentException("io_reject.groups: expected group >= 0 and output mapping");
            int port = YamlScalars.toInt(spec.get("port"), 0);
            int sdkPort = YamlScalars.toInt(spec.get("sdk_port"), port - 1);
            if (port < 1 || port > 8 || sdkPort < 0 || sdkPort > 255 || protectedPorts.contains(port)
                    || !ports.add(port) || !sdkPorts.add(sdkPort)) throw new IllegalArgumentException("io_reject: invalid, protected or duplicate output for group " + group);
            String timer = Objects.toString(spec.get("timer"), "");
            String line = Objects.toString(spec.get("line"), "Out" + port);
            if (mode.equals("timer") && (!timer.matches("Timer[1-8]") || timer.equals("Timer5") || !timers.add(timer) || !line.equals("Out" + port)))
                throw new IllegalArgumentException("io_reject: explicit unique Timer1..8 (except camera Timer5) and matching OutN required");
            groups.put(group, new Output(port, sdkPort, YamlScalars.toBool(spec.get("active_high"), true),
                    YamlScalars.toBool(spec.get("enable_on_write"), true), timer, line));
        });
        int pulse = YamlScalars.toInt(cfg.get("pulse_ms"), 80);
        int ack = YamlScalars.toInt(cfg.get("ack_timeout_ms"), 3000);
        int queue = YamlScalars.toInt(cfg.get("queue_size"), 64);
        int shutdown = YamlScalars.toInt(cfg.get("shutdown_timeout_ms"), 5000);
        if (pulse < 0 || ack < 1 || queue < 1 || shutdown < 1 || (enabled && groups.isEmpty()))
            throw new IllegalArgumentException("io_reject: invalid timings/queue or empty enabled groups");
        return new IoRejectConfig(enabled, pulse, ack, queue, shutdown, Map.copyOf(groups), mode);
    }
}
