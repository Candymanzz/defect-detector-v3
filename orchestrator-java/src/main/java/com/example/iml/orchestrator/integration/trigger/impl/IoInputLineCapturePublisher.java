package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Line-capture publish paths, debounce clock, and DI3 delayed capture scheduling.
 */
final class IoInputLineCapturePublisher {

    private final Logger log;
    private final IoInputDiscreteConfig ioInputConfig;
    private final InspectionTriggerBus bus;
    private final IoInputLiveState live;
    private final IoInputWorkSessionDirection workSessionDirection;
    private final ManualLineDirectionService manualLineDirection;
    private final ScheduledExecutorService captureDelayExecutor;
    private final IoInputCaptureDirectionGate gate;
    private final IoInputLineCaptureFire fire;
    private volatile ScheduledFuture<?> delayedCaptureTask;

    IoInputLineCapturePublisher(
            Logger log,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            IoInputLiveState live,
            IoInputWorkSessionDirection workSessionDirection,
            IoInputDirectionAutoCapture directionAutoCapture,
            ManualLineDirectionService manualLineDirection,
            ScheduledExecutorService captureDelayExecutor,
            BooleanSupplier usesAutoDirection
    ) {
        this.log = log;
        this.ioInputConfig = ioInputConfig;
        this.bus = bus;
        this.live = live;
        this.workSessionDirection = workSessionDirection;
        this.manualLineDirection = manualLineDirection;
        this.captureDelayExecutor = captureDelayExecutor;
        this.gate = new IoInputCaptureDirectionGate(log, ioInputConfig, live);
        this.fire = new IoInputLineCaptureFire(
                log,
                ioInputConfig,
                bus,
                live,
                directionAutoCapture,
                usesAutoDirection,
                gate,
                this::publishLineCapture
        );
    }

    void tryInstantCaptureWithWorkSession() {
        if (live.captureFiredThisPulse) {
            return;
        }
        if (!gate.allowsCaptureForSelectedDirection()) {
            return;
        }
        if (!workSessionDirection.allowsCapture(
                ioInputConfig.requireWork(),
                live.isEffectiveWork(ioInputConfig),
                // при UI-направлении сессия не режет reverse: фильтр уже в allowsCaptureForSelectedDirection
                ioInputConfig.requireDirection() && manualLineDirection == null
        )) {
            if (ioInputConfig.requireWork() && !live.isEffectiveWork(ioInputConfig)) {
                log.info("io_input_trigger skip: conveyor not running (work=0)");
            } else if (!workSessionDirection.sessionDirectionKnown()) {
                log.info("io_input_trigger skip: no session direction — DI1↑ (заведение) required after restart/stop");
            } else {
                log.info(
                        "io_input_trigger skip: reverse session latched at work start (raw={})",
                        workSessionDirection.sessionDirectionRaw() ? 1 : 0
                );
            }
            return;
        }
        if (!gate.passDebounce()) {
            return;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        List<Integer> targetCameras = gate.resolveTargetCameras();
        int published = publishLineCapture(targetCameras);
        if (published > 0) {
            live.captureFiredThisPulse = true;
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger instant capture session_forward={} session_raw={} cameras={} target={} dispatch_ms={}",
                    workSessionDirection.sessionDirectionActive() ? 1 : 0,
                    workSessionDirection.sessionDirectionRaw() ? 1 : 0,
                    published,
                    IoInputCaptureDirectionGate.formatCameraTarget(targetCameras),
                    dispatchMs
            );
        }
    }

    void scheduleCaptureAfterDi3Open() {
        cancelDelayedCapture();
        int delayMs = ioInputConfig.captureDelayMs();
        if (delayMs <= 0) {
            log.info("io_input_trigger DI3 pulse open — immediate capture");
            fireLineCapture();
            return;
        }
        log.info("io_input_trigger DI3 pulse open — capture scheduled in {} ms", delayMs);
        delayedCaptureTask = captureDelayExecutor.schedule(
                () -> {
                    if (!live.triggerActive || live.captureFiredThisPulse) {
                        return;
                    }
                    fireLineCapture();
                },
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    void cancelDelayedCapture() {
        ScheduledFuture<?> task = delayedCaptureTask;
        if (task != null) {
            task.cancel(false);
            delayedCaptureTask = null;
        }
    }

    void fireLineCapture() {
        fire.fireLineCapture();
    }

    int publishLineCapture(List<Integer> targetCameras) {
        if (ioInputConfig.externalHardwareCapture()) {
            return bus.dispatchLineBroadcastWithoutPrefire("io_input", targetCameras);
        }
        long seq = bus.prefireLineBroadcast("io_input", targetCameras);
        return bus.dispatchLineBroadcast("io_input", seq, targetCameras);
    }

    boolean allowsCaptureForSelectedDirection() {
        return gate.allowsCaptureForSelectedDirection();
    }

    String effectiveDirectionWire() {
        return gate.effectiveDirectionWire();
    }

    String directionSourceLabel() {
        return gate.directionSourceLabel();
    }

    List<Integer> resolveTargetCameras() {
        return gate.resolveTargetCameras();
    }

    static String formatCameraTarget(List<Integer> targetCameras) {
        return IoInputCaptureDirectionGate.formatCameraTarget(targetCameras);
    }

    void tryCommitLineCapture(String source, boolean ignoreDirectionCheck) {
        fire.tryCommitLineCapture(source, ignoreDirectionCheck);
    }

    void publishDebounced() {
        fire.publishDebounced();
    }
}
