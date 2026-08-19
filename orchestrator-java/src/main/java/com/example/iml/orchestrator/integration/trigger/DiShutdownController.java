package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DI shutdown (по умолчанию DI4=1): гасит вспышки и запрашивает остановку оркестратора.
 * Срабатывает на фронт 0→1 один раз за процесс.
 */
public final class DiShutdownController {

    private final Logger log;
    private final int shutdownPort;
    private final OrchestratorStopSignal stopSignal;
    private final FanOutCoordinator fanOut;
    private final AtomicBoolean lastActive = new AtomicBoolean(false);
    private final AtomicBoolean fired = new AtomicBoolean(false);

    public DiShutdownController(
            Logger log,
            int shutdownPort,
            OrchestratorStopSignal stopSignal,
            FanOutCoordinator fanOut
    ) {
        this.log = log;
        this.shutdownPort = shutdownPort;
        this.stopSignal = stopSignal;
        this.fanOut = fanOut;
    }

    public int shutdownPort() {
        return shutdownPort;
    }

    public void onDiChange(IoInputDiChange change) {
        if (change == null || shutdownPort < 1 || change.diPort() != shutdownPort) {
            return;
        }
        boolean wasActive = lastActive.getAndSet(change.active());
        if (!change.active() || wasActive) {
            return;
        }
        if (!fired.compareAndSet(false, true)) {
            return;
        }
        String reason = "di" + shutdownPort + "_shutdown";
        log.warn(
                "DI{}=1 — безопасное выключение: lights Off, vision_ready=0, vision_fault=1, stop orchestrator ({})",
                shutdownPort,
                reason
        );
        if (fanOut != null) {
            try {
                fanOut.signalVisionReady(false);
            } catch (Exception e) {
                log.warn("DI{} shutdown: vision_ready=0 failed: {}", shutdownPort, e.getMessage());
            }
            try {
                fanOut.signalVisionFault(true);
            } catch (Exception e) {
                log.warn("DI{} shutdown: vision_fault=1 failed: {}", shutdownPort, e.getMessage());
            }
        }
        try {
            LightsShutdown.run(reason);
        } catch (Exception e) {
            log.warn("DI{} shutdown: lights failed: {}", shutdownPort, e.getMessage());
        }
        if (stopSignal != null) {
            stopSignal.request(reason);
        }
    }
}
