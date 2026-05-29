package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record InspectionTriggerConfig(UdpTriggerConfig udp) {

    public static InspectionTriggerConfig parse(Map<String, Object> integration) {
        UdpTriggerConfig defaults = UdpTriggerConfig.defaults();
        if (integration == null) {
            return new InspectionTriggerConfig(defaults);
        }
        Object raw = integration.get("inspection_trigger");
        if (!(raw instanceof Map<?, ?> root)) {
            return new InspectionTriggerConfig(defaults);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) root;
        Object udpRaw = m.get("udp");
        if (!(udpRaw instanceof Map<?, ?> udpMap)) {
            return new InspectionTriggerConfig(defaults);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> udp = (Map<String, Object>) udpMap;
        boolean enabled = YamlScalars.toBool(udp.get("enabled"), defaults.enabled());
        String bindHost = udp.get("bind_host") != null ? String.valueOf(udp.get("bind_host")) : defaults.bindHost();
        int bindPort = Math.max(1, Math.min(65535, YamlScalars.toInt(udp.get("bind_port"), defaults.bindPort())));
        String format = udp.get("format") != null ? String.valueOf(udp.get("format")).trim().toLowerCase() : defaults.format();
        int defaultCameraId = Math.max(0, YamlScalars.toInt(udp.get("default_camera_id"), defaults.defaultCameraId()));
        int debounceMs = Math.max(0, YamlScalars.toInt(udp.get("debounce_ms"), defaults.debounceMs()));
        List<String> allowed = parseAllowedHosts(udp.get("allowed_remote_hosts"));
        return new InspectionTriggerConfig(
                new UdpTriggerConfig(enabled, bindHost, bindPort, format, defaultCameraId, debounceMs, allowed)
        );
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
