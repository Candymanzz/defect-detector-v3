package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared collaborators for DI1/DI2/DI3 port handlers.
 */
final class IoInputDiPortContext {

    final Logger log;
    final IoInputDiscreteConfig ioInputConfig;
    final IoInputLiveState live;
    final IoInputDirectionLatch directionLatch;
    final IoInputDirectionAutoCapture directionAutoCapture;
    final IoInputWorkSessionDirection workSessionDirection;
    final IoInputDirectionWaiter directionWaiter;
    final LineDiscreteTriggerEvaluator evaluator;
    final ManualLineDirectionService manualLineDirection;
    final Runnable onLineWorkChanged;
    final IoInputLineCapturePublisher capturePublisher;
    final AtomicBoolean lineWorkActive;

    IoInputDiPortContext(
            Logger log,
            IoInputDiscreteConfig ioInputConfig,
            IoInputLiveState live,
            IoInputDirectionLatch directionLatch,
            IoInputDirectionAutoCapture directionAutoCapture,
            IoInputWorkSessionDirection workSessionDirection,
            IoInputDirectionWaiter directionWaiter,
            LineDiscreteTriggerEvaluator evaluator,
            ManualLineDirectionService manualLineDirection,
            Runnable onLineWorkChanged,
            IoInputLineCapturePublisher capturePublisher,
            AtomicBoolean lineWorkActive
    ) {
        this.log = log;
        this.ioInputConfig = ioInputConfig;
        this.live = live;
        this.directionLatch = directionLatch;
        this.directionAutoCapture = directionAutoCapture;
        this.workSessionDirection = workSessionDirection;
        this.directionWaiter = directionWaiter;
        this.evaluator = evaluator;
        this.manualLineDirection = manualLineDirection;
        this.onLineWorkChanged = onLineWorkChanged;
        this.capturePublisher = capturePublisher;
        this.lineWorkActive = lineWorkActive;
    }

    boolean usesAutoDirection() {
        return false;
    }

    boolean mapDirection(boolean rawDiActive) {
        return ioInputConfig.directionInvert() ? !rawDiActive : rawDiActive;
    }

    void updateLineWork(boolean work) {
        boolean previous = lineWorkActive.getAndSet(work);
        if (previous != work) {
            log.info("io_input_trigger line work {} -> {}", previous ? 1 : 0, work ? 1 : 0);
            onLineWorkChanged.run();
        }
    }

    void logDirectionWaitTimeout() {
        log.info(
                "io_input_trigger skip: direction timeout after {} ms (current DI2={})",
                ioInputConfig.directionWaitMs(),
                live.directionActive ? 1 : 0
        );
    }
}
