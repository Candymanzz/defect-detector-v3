package com.example.iml.orchestrator.integration.trigger.config;

import java.util.List;

public record UdpTriggerConfig(
        boolean enabled,
        String bindHost,
        int bindPort,
        String format,
        int defaultCameraId,
        int debounceMs,
        List<String> allowedRemoteHosts
) {
    public static UdpTriggerConfig defaults() {
        return new UdpTriggerConfig(true, "0.0.0.0", 9100, "json", 0, 100, List.of());
    }
}
