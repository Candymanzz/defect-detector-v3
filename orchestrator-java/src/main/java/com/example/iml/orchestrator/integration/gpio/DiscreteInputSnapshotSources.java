package com.example.iml.orchestrator.integration.gpio;

import com.example.iml.orchestrator.integration.trigger.config.GpioTriggerConfig;

public final class DiscreteInputSnapshotSources {

    private DiscreteInputSnapshotSources() {
    }

    public static DiscreteInputSnapshotSource create(GpioTriggerConfig config) {
        if ("hikrobot_mv_io".equalsIgnoreCase(config.backend())) {
            return new HikrobotMvIoDiscreteInputSnapshotSource(config);
        }
        return new SysfsDiscreteInputSnapshotSource(config);
    }

    public static boolean isConfigured(GpioTriggerConfig config) {
        return config.fullyConfigured();
    }
}
