package com.example.iml.orchestrator.integration.gpio;

import com.example.iml.orchestrator.integration.trigger.config.GpioTriggerConfig;

/** Linux sysfs: три отдельных value-файла. */
public final class SysfsDiscreteInputSnapshotSource implements DiscreteInputSnapshotSource {

    private final SysfsDigitalInputReader work;
    private final SysfsDigitalInputReader direction;
    private final SysfsDigitalInputReader trigger;

    public SysfsDiscreteInputSnapshotSource(GpioTriggerConfig config) {
        this.work = new SysfsDigitalInputReader(config.work().path(), config.activeValue());
        this.direction = new SysfsDigitalInputReader(config.direction().path(), config.activeValue());
        this.trigger = new SysfsDigitalInputReader(config.trigger().path(), config.activeValue());
    }

    @Override
    public DiscreteInputSnapshot readSnapshot() throws Exception {
        return new DiscreteInputSnapshot(
                work.readActive(),
                direction.readActive(),
                trigger.readActive()
        );
    }
}
