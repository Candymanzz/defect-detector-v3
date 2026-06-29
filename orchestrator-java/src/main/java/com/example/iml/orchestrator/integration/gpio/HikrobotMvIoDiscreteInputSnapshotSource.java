package com.example.iml.orchestrator.integration.gpio;

import com.example.iml.orchestrator.integration.hikrobot.mvio.HikrobotMvIoClient;
import com.example.iml.orchestrator.integration.trigger.config.GpioTriggerConfig;

/** Hikrobot VC DI через MvIOInterfaceBox.dll (JNA) в оркестраторе. */
public final class HikrobotMvIoDiscreteInputSnapshotSource implements DiscreteInputSnapshotSource {

    private final HikrobotMvIoClient client;
    private final int workPort;
    private final int directionPort;
    private final int triggerPort;

    public HikrobotMvIoDiscreteInputSnapshotSource(GpioTriggerConfig config) {
        this.client = new HikrobotMvIoClient(
                config.comPort(),
                config.activeValue(),
                config.dllDirectory()
        );
        this.workPort = config.workPort();
        this.directionPort = config.directionPort();
        this.triggerPort = config.triggerPort();
    }

    @Override
    public DiscreteInputSnapshot readSnapshot() {
        return new DiscreteInputSnapshot(
                client.readPortActive(workPort),
                client.readPortActive(directionPort),
                client.readPortActive(triggerPort)
        );
    }

    @Override
    public void close() {
        client.close();
    }
}
