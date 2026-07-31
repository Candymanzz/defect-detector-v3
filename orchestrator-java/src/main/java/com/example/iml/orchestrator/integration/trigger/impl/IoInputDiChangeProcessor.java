package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * DI change FSM: work / direction / trigger ports → capture decisions and line-work updates.
 */
final class IoInputDiChangeProcessor {

    private final IoInputDiPortContext ctx;
    private final IoInputDi1WorkPortHandler workHandler;
    private final IoInputDi2DirectionPortHandler directionHandler;
    private final IoInputDi3TriggerPortHandler triggerHandler;
    private final Consumer<IoInputDiChange> notifyDiChangeListeners;

    IoInputDiChangeProcessor(
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
            AtomicBoolean lineWorkActive,
            Consumer<IoInputDiChange> notifyDiChangeListeners
    ) {
        this.ctx = new IoInputDiPortContext(
                log,
                ioInputConfig,
                live,
                directionLatch,
                directionAutoCapture,
                workSessionDirection,
                directionWaiter,
                evaluator,
                manualLineDirection,
                onLineWorkChanged,
                capturePublisher,
                lineWorkActive
        );
        IoInputDiTriggerDecisionSupport decisions = new IoInputDiTriggerDecisionSupport(ctx);
        this.workHandler = new IoInputDi1WorkPortHandler(ctx, decisions);
        this.directionHandler = new IoInputDi2DirectionPortHandler(ctx, decisions);
        this.triggerHandler = new IoInputDi3TriggerPortHandler(ctx, decisions);
        this.notifyDiChangeListeners = notifyDiChangeListeners;
    }

    void applyDiChange(IoInputDiChange change) {
        notifyDiChangeListeners.accept(change);
        int port = change.diPort();
        boolean active = change.active();
        if (port == ctx.ioInputConfig.workPort()) {
            workHandler.onWorkChange(active);
            return;
        }
        if (port == ctx.ioInputConfig.directionPort()) {
            directionHandler.onDirectionChange(active);
            return;
        }
        if (port == ctx.ioInputConfig.triggerPort()) {
            triggerHandler.onTriggerChange(active);
            return;
        }
        if (port > 0) {
            ctx.log.debug("io_input_trigger ignored di={} value={}", port, active ? 1 : 0);
        } else {
            ctx.log.debug("io_input_trigger legacy payload without di port ignored");
        }
    }

    /**
     * Latch-режим (DI2=1 вооружает навсегда) отключён: при {@code require_direction}
     * проверяем уровень DI2 на фронте DI3.
     */
    boolean usesAutoDirection() {
        return ctx.usesAutoDirection();
    }

    void logDirectionWaitTimeout() {
        ctx.logDirectionWaitTimeout();
    }
}
