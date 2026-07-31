package com.example.iml.orchestrator.integration.fanout.plc;

import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import org.apache.logging.log4j.Logger;

/**
 * Sticky vision_ready / vision_fault с учётом эталона, health-gate и shutdown-prep.
 */
public final class PlcVisionLevelController {
    private final Logger log;
    private final PlcFinsPublisher plcPublisher;
    private volatile ServiceHealthGate healthGate;
    private volatile ClientWsSessionState lastSessionState = ClientWsSessionState.NO_REFERENCE;
    /** Sticky: DI4 (БП) → ready=0 / fault=1 до завершения процесса; refresh не снимает. */
    private volatile boolean shutdownPrepActive;

    public PlcVisionLevelController(Logger log, PlcFinsPublisher plcPublisher) {
        this.log = log;
        this.plcPublisher = plcPublisher;
    }

    public void onSessionState(ClientWsSessionState state) {
        lastSessionState = state == null ? ClientWsSessionState.NO_REFERENCE : state;
        refreshPlcLevels();
    }

    public void setHealthGate(ServiceHealthGate healthGate) {
        this.healthGate = healthGate;
        if (healthGate != null) {
            healthGate.setOnChanged(this::refreshPlcLevels);
        }
        refreshPlcLevels();
    }

    /**
     * Подготовка к завершению (триггер БП по DI4): vision_ready→0, vision_fault→1, sticky.
     * Пишет FINS синхронно, чтобы биты ушли в ПЛК до graceful stop / {@code System.exit}.
     */
    public void enterShutdownPrep(String reason) {
        if (shutdownPrepActive) {
            return;
        }
        shutdownPrepActive = true;
        log.warn(
                "plc fins shutdown prep — vision_ready=0 vision_fault=1 reason={}",
                reason == null || reason.isBlank() ? "shutdown_prep" : reason.trim()
        );
        if (plcPublisher != null) {
            try {
                plcPublisher.flushVisionLevels(false, true);
            } catch (Exception e) {
                log.warn("plc fins shutdown prep flush failed: {}", e.getMessage());
                refreshPlcLevels();
                return;
            }
        }
        refreshPlcLevels();
    }

    public boolean isShutdownPrepActive() {
        return shutdownPrepActive;
    }

    /**
     * Пересчёт sticky vision_ready / vision_fault с учётом эталона и {@link ServiceHealthGate}.
     * При {@link #enterShutdownPrep} всегда ready=0 / fault=1.
     */
    public void refreshPlcLevels() {
        boolean referenceActive = lastSessionState != null
                && lastSessionState != ClientWsSessionState.NO_REFERENCE;
        ServiceHealthGate gate = healthGate;
        boolean healthy = gate == null || gate.healthy();
        boolean ready;
        boolean fault;
        if (shutdownPrepActive) {
            ready = false;
            fault = true;
        } else {
            ready = referenceActive && healthy;
            fault = !healthy;
        }
        signalVisionReady(ready);
        signalVisionFault(fault);
        log.info(
                "plc fins session_state={} vision_ready={} vision_fault={} (reference_active={} healthy={} shutdown_prep={} unhealthy={})",
                lastSessionState == null ? "null" : lastSessionState.name(),
                ready,
                fault,
                referenceActive,
                healthy,
                shutdownPrepActive,
                gate == null ? "[]" : gate.unhealthyReasons()
        );
    }

    public void signalVisionReady(boolean ready) {
        if (plcPublisher == null) {
            return;
        }
        plcPublisher.setVisionReady(ready);
    }

    public void signalVisionFault(boolean fault) {
        if (plcPublisher == null) {
            return;
        }
        plcPublisher.setVisionFault(fault);
    }
}
