package com.example.iml.orchestrator.integration.io.mvs;

import org.apache.logging.log4j.Logger;

public final class MvsIoDevices {
    private MvsIoDevices() { }
    public static MvsIoDevice create(MvsIoConfig config, Logger logger) {
        return config.backend().equals("mv_io")
                ? new JnaMvIoDevice(config, org.apache.logging.log4j.LogManager.getLogger(JnaMvIoDevice.class))
                : new JnaMvsIoDevice(config, org.apache.logging.log4j.LogManager.getLogger(JnaMvsIoDevice.class));
    }
}
