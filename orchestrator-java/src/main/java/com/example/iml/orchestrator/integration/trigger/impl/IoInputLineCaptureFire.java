package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** DI3 fire / commit / debounced broadcast paths. */
final class IoInputLineCaptureFire {

    private final Logger log;
    private final IoInputDiscreteConfig ioInputConfig;
    private final InspectionTriggerBus bus;
    private final IoInputLiveState live;
    private final IoInputDirectionAutoCapture directionAutoCapture;
    private final BooleanSupplier usesAutoDirection;
    private final IoInputCaptureDirectionGate gate;
    private final Function<List<Integer>, Integer> publishLineCapture;

    IoInputLineCaptureFire(
            Logger log,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            IoInputLiveState live,
            IoInputDirectionAutoCapture directionAutoCapture,
            BooleanSupplier usesAutoDirection,
            IoInputCaptureDirectionGate gate,
            Function<List<Integer>, Integer> publishLineCapture
    ) {
        this.log = log;
        this.ioInputConfig = ioInputConfig;
        this.bus = bus;
        this.live = live;
        this.directionAutoCapture = directionAutoCapture;
        this.usesAutoDirection = usesAutoDirection;
        this.gate = gate;
        this.publishLineCapture = publishLineCapture;
    }

    void fireLineCapture() {
        if (live.captureFiredThisPulse) {
            return;
        }
        if (live.directionActive && live.captureFiredThisDi2Window) {
            log.info("io_input_trigger skip: холостой DI3 (уже сняли при DI2=1)");
            return;
        }
        if (ioInputConfig.requireWork() && !live.isEffectiveWork(ioInputConfig)) {
            log.info("io_input_trigger skip: conveyor not running (work=0)");
            return;
        }
        if (ioInputConfig.requireDirection() && usesAutoDirection.getAsBoolean()
                && !directionAutoCapture.isDirectionArmed()) {
            log.info("io_input_trigger skip: await DI2=1 before capture (direction not armed)");
            return;
        }
        if (!gate.allowsCaptureForSelectedDirection()) {
            return;
        }
        if (!gate.passDebounce()) {
            return;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        List<Integer> targetCameras = gate.resolveTargetCameras();
        int published = publishLineCapture.apply(targetCameras);
        if (published > 0) {
            live.captureFiredThisPulse = true;
            if (live.directionActive) {
                live.captureFiredThisDi2Window = true;
            }
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger DI3 capture direction={} source={} cameras={} target={} dispatch_ms={} hardware={}",
                    gate.effectiveDirectionWire(),
                    gate.directionSourceLabel(),
                    published,
                    IoInputCaptureDirectionGate.formatCameraTarget(targetCameras),
                    dispatchMs,
                    ioInputConfig.externalHardwareCapture()
            );
        }
    }

    void tryCommitLineCapture(String source, boolean ignoreDirectionCheck) {
        if (live.captureFiredThisPulse) {
            return;
        }
        if (ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING && !live.triggerActive) {
            return;
        }
        if (ioInputConfig.requireWork() && !live.isEffectiveWork(ioInputConfig)) {
            log.info("io_input_trigger skip: conveyor not running (work=0)");
            return;
        }
        if (!ignoreDirectionCheck && !gate.allowsCaptureForSelectedDirection()) {
            return;
        }
        if (!gate.passDebounce()) {
            return;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        if (published > 0) {
            live.captureFiredThisPulse = true;
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger capture direction={} cameras={} ({}) dispatch_ms={}",
                    live.directionActive ? 1 : 0,
                    published,
                    source,
                    dispatchMs
            );
        }
    }

    void publishDebounced() {
        long triggerReceivedMs = System.currentTimeMillis();
        if (!gate.passDebounce()) {
            return;
        }
        int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        if (published > 0) {
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info("io_input_trigger line broadcast cameras={} dispatch_ms={}", published, dispatchMs);
        }
    }
}
