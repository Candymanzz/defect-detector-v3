package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record InspectionTriggerConfig(
        UdpTriggerConfig udp,
        IoInputDiscreteConfig ioInput,
        TwoPhaseTriggerConfig twoPhase
) {

    public InspectionTriggerConfig(UdpTriggerConfig udp, IoInputDiscreteConfig ioInput) {
        this(udp, ioInput, TwoPhaseTriggerConfig.defaults());
    }

    public static InspectionTriggerConfig parse(Map<String, Object> integration) {
        UdpTriggerConfig udpDefaults = UdpTriggerConfig.defaults();
        if (integration == null) {
            IoInputDiscreteConfig ioInput = IoInputDiscreteConfig.parse(null, udpDefaults.debounceMs());
            return new InspectionTriggerConfig(udpDefaults, ioInput, TwoPhaseTriggerConfig.defaults());
        }
        Object raw = integration.get("inspection_trigger");
        if (!(raw instanceof Map<?, ?> root)) {
            IoInputDiscreteConfig ioInput = IoInputDiscreteConfig.parse(null, udpDefaults.debounceMs());
            return new InspectionTriggerConfig(udpDefaults, ioInput, TwoPhaseTriggerConfig.defaults());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) root;
        IoInputDiscreteConfig ioInput = IoInputDiscreteConfig.parse(integration, udpDefaults.debounceMs());
        TwoPhaseTriggerConfig twoPhase = TwoPhaseTriggerConfig.parse(m);
        Object udpRaw = m.get("udp");
        if (!(udpRaw instanceof Map<?, ?> udpMap)) {
            return new InspectionTriggerConfig(udpDefaults, ioInput, twoPhase);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> udp = (Map<String, Object>) udpMap;
        boolean enabled = YamlScalars.toBool(udp.get("enabled"), udpDefaults.enabled());
        String bindHost = udp.get("bind_host") != null ? String.valueOf(udp.get("bind_host")) : udpDefaults.bindHost();
        int bindPort = Math.max(1, Math.min(65535, YamlScalars.toInt(udp.get("bind_port"), udpDefaults.bindPort())));
        String format = udp.get("format") != null ? String.valueOf(udp.get("format")).trim().toLowerCase() : udpDefaults.format();
        int defaultCameraId = Math.max(0, YamlScalars.toInt(udp.get("default_camera_id"), udpDefaults.defaultCameraId()));
        int debounceMs = Math.max(0, YamlScalars.toInt(udp.get("debounce_ms"), udpDefaults.debounceMs()));
        List<String> allowed = parseAllowedHosts(udp.get("allowed_remote_hosts"));
        ioInput = IoInputDiscreteConfig.parse(integration, debounceMs);
        return new InspectionTriggerConfig(
                new UdpTriggerConfig(enabled, bindHost, bindPort, format, defaultCameraId, debounceMs, allowed),
                ioInput,
                twoPhase
        );
    }

    public boolean usesIoInputMonitor() {
        return udp.enabled() && "io_input".equalsIgnoreCase(udp.format());
    }

    private static List<String> parseAllowedHosts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String host = String.valueOf(item).trim();
            if (!host.isEmpty()) {
                out.add(host);
            }
        }
        return List.copyOf(out);
    }
}
