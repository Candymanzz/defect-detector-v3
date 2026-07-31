package com.example.iml.orchestrator.integration.bootstrap.service.trigger;

import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DI↑ блока питания ({@code shutdown_prep_port}, по умолчанию DI4):
 * vision_ready→0, vision_fault→1, затем graceful stop оркестратора.
 */
public final class ShutdownPrepDiWire {

    private final Logger log;

    public ShutdownPrepDiWire(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void wire(
            TriggerCollaboratorView collaborators,
            InspectionTriggerConfig triggerCfg,
            InspectionTriggerRuntime triggerRuntime
    ) {
        if (!triggerCfg.usesIoInputMonitor() || !triggerCfg.ioInput().shutdownPrepOnWorkHigh()) {
            return;
        }
        FanOutCoordinator fanOut = collaborators.fanOut();
        OrchestratorStopSignal stopSignal = collaborators.stopSignal();
        int prepPort = triggerCfg.ioInput().shutdownPrepPort();
        AtomicBoolean armed = new AtomicBoolean(false);
        triggerRuntime.addDiChangeListener(change -> {
            if (change.diPort() != prepPort || !change.active()) {
                return;
            }
            if (!armed.compareAndSet(false, true)) {
                return;
            }
            log.warn(
                    "DI{}↑ power-supply trigger — shutdown prep then process exit (vision_ready=0, vision_fault=1)",
                    prepPort
            );
            if (fanOut != null) {
                fanOut.enterShutdownPrep("di" + prepPort + "_power_supply");
            }
            if (stopSignal != null) {
                stopSignal.request("di" + prepPort + "_power_supply");
            }
        });
        log.info(
                "inspection_trigger shutdown_prep_on_work_high=true — DI{}↑ → vision_ready=0 / vision_fault=1 → stop → System.exit",
                prepPort
        );
    }
}
