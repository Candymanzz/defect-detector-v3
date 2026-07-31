package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;

/**
 * Shared trigger decision / missing-direction handling for DI port handlers.
 */
final class IoInputDiTriggerDecisionSupport {

    private final IoInputDiPortContext ctx;

    IoInputDiTriggerDecisionSupport(IoInputDiPortContext ctx) {
        this.ctx = ctx;
    }

    void handleDi3RisingCapture() {
        if (ctx.usesAutoDirection() || ctx.ioInputConfig.di3Only()) {
            return;
        }
        if (ctx.live.directionActive || !ctx.ioInputConfig.requireDirection()) {
            ctx.capturePublisher.tryCommitLineCapture("DI3 edge", false);
        } else {
            handleMissingDirection();
        }
    }

    void evaluateTriggerDecision() {
        if (ctx.ioInputConfig.directionLatchOnWork()) {
            evaluateWorkSessionDecision();
            return;
        }

        boolean effectiveWork = ctx.live.isEffectiveWork(ctx.ioInputConfig);
        boolean effectiveDirection = !ctx.ioInputConfig.requireDirection() || ctx.live.directionActive;
        LineDiscreteTriggerEvaluator.Decision decision = ctx.evaluator.evaluate(
                effectiveWork,
                effectiveDirection,
                ctx.live.triggerActive,
                ctx.ioInputConfig.requireDirection(),
                ctx.ioInputConfig.requireWork()
        );
        switch (decision) {
            case NONE -> { }
            case SKIP_NOT_READY -> ctx.log.info("io_input_trigger skip: conveyor not running (work=0)");
            case SKIP_WRONG_DIRECTION -> handleMissingDirection();
            case FIRE -> {
                ctx.log.info(
                        "io_input_trigger capture on DI3 edge direction={}",
                        ctx.live.directionActive ? 1 : 0
                );
                ctx.capturePublisher.publishDebounced();
            }
            default -> { }
        }
    }

    private void evaluateWorkSessionDecision() {
        boolean effectiveWork = ctx.live.isEffectiveWork(ctx.ioInputConfig);
        if (!ctx.workSessionDirection.allowsCapture(
                ctx.ioInputConfig.requireWork(),
                effectiveWork,
                ctx.ioInputConfig.requireDirection()
        )) {
            if (ctx.ioInputConfig.requireWork() && !effectiveWork) {
                ctx.log.info("io_input_trigger skip: conveyor not running (work=0)");
            } else if (!ctx.workSessionDirection.sessionDirectionKnown()) {
                ctx.log.info("io_input_trigger skip: session direction not latched yet (await DI2 after DI1)");
            } else {
                ctx.log.info("io_input_trigger skip: session direction=0 (latched at work start)");
            }
            return;
        }
        LineDiscreteTriggerEvaluator.Decision sessionDecision = ctx.evaluator.evaluate(
                true,
                true,
                ctx.live.triggerActive,
                false,
                false
        );
        if (sessionDecision == LineDiscreteTriggerEvaluator.Decision.FIRE) {
            ctx.log.info(
                    "io_input_trigger capture on DI3 edge session_direction={}",
                    ctx.workSessionDirection.sessionDirectionActive() ? 1 : 0
            );
            ctx.capturePublisher.publishDebounced();
        }
    }

    void handleMissingDirection() {
        if (ctx.ioInputConfig.requireDirection() && ctx.ioInputConfig.directionWaitMs() > 0) {
            ctx.directionWaiter.begin("DI3 edge, polling DI2");
            return;
        }
        ctx.log.info(
                "io_input_trigger skip: DI2=0 at DI3 edge — await DI2=1 during pulse (up to {} ms)",
                ctx.ioInputConfig.directionWaitMs() > 0 ? ctx.ioInputConfig.directionWaitMs() : "pulse end"
        );
    }
}
