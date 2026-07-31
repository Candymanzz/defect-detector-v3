package com.example.iml.orchestrator.integration.bootstrap.service.trigger;

import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringSink;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashConfig;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashController;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Wire interval-flash controller (DI-driven) or always-on lighting startup.
 */
public final class IntervalFlashWire {

    private final Logger log;

    public IntervalFlashWire(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void wire(
            TriggerConfigView config,
            TriggerCollaboratorView collaborators,
            TriggerWiringSink sink
    ) {
        IntervalFlashConfig intervalFlashCfg = IntervalFlashConfig.fromRootYaml(config.root());
        if (intervalFlashCfg.enabled() && collaborators.lightClient().isEnabled()) {
            IntervalFlashController intervalFlashController =
                    new IntervalFlashController(log, collaborators.lightClient(), intervalFlashCfg);
            LightsShutdown.bindIntervalFlash(intervalFlashController);
            sink.triggerRuntime().addDiChangeListener(intervalFlashController::onDiChange);
            collaborators.lightClient().setCaptureLightingActive(intervalFlashController::captureLightingActive);
            intervalFlashController.setFlushDeferredBrightness(collaborators.lightClient()::flushDeferredBrightness);
            if (sink.lineCaptureCoordinator() != null) {
                sink.lineCaptureCoordinator().setOnFirstFrameCaptured(intervalFlashController::onFirstFrameCaptured);
            }
            intervalFlashController.armStartDark();
            log.info(
                    "interval_flash enabled — idle_on={} (DI{} {}); DI{} {} → On + Off (off_delay_ms={}, off_on_first_frame={}); авто-On через {} ms; hold_mode={}",
                    intervalFlashCfg.idleOnEnabled(),
                    intervalFlashCfg.idlePort(),
                    intervalFlashCfg.idleEdge().name().toLowerCase(),
                    intervalFlashCfg.triggerPort(),
                    intervalFlashCfg.triggerEdge().name().toLowerCase(),
                    intervalFlashCfg.offDelayMs(),
                    intervalFlashCfg.offOnFirstFrame(),
                    intervalFlashCfg.onReengageDelayMs(),
                    collaborators.lightClient().isHoldMode()
            );
        } else if (intervalFlashCfg.enabled()) {
            log.warn("interval_flash enabled, но light_servers выключены — вспышки по DI не активны");
        } else if (collaborators.lightClient().isEnabled()) {
            collaborators.lightClient().setAfterBrightnessApplied(null);
            boolean alwaysOn = collaborators.lightClient().bankAllOn("always-on-startup");
            log.info(
                    "always_on lighting startup bankOn={} hold_mode={} interval_flash={}",
                    alwaysOn,
                    collaborators.lightClient().isHoldMode(),
                    false
            );
        }
    }
}
