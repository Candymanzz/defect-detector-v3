package com.example.iml.orchestrator.integration.trigger.impl;

/**
 * DI1 / work-port FSM: line-work updates, direction unlatch, work-session hooks.
 */
final class IoInputDi1WorkPortHandler {

    private final IoInputDiPortContext ctx;
    private final IoInputDiTriggerDecisionSupport decisions;

    IoInputDi1WorkPortHandler(IoInputDiPortContext ctx, IoInputDiTriggerDecisionSupport decisions) {
        this.ctx = ctx;
        this.decisions = decisions;
    }

    void onWorkChange(boolean active) {
        boolean previousWork = ctx.live.workActive;
        ctx.live.workActive = active;
        ctx.updateLineWork(active);
        // Как IoInputMonitor disarm_on_work_low: DI1↓ снимает direction latch.
        // Иначе Java ждёт wait_frame без DO5 → timeout / чужие кадры с Line0-шума.
        if (!active && previousWork && ctx.ioInputConfig.directionLatch() && ctx.live.directionLatched) {
            ctx.live.directionLatched = false;
            ctx.live.directionActive = false;
            ctx.log.info("io_input_trigger direction unlatched (DI{}↓) — жди DI2=1 снова",
                    ctx.ioInputConfig.workPort());
        }
        if (ctx.ioInputConfig.directionLatchOnWork()) {
            if (active && !previousWork) {
                ctx.workSessionDirection.onWorkStarted(ctx.live.triggerActive, ctx.log);
                ctx.workSessionDirection.onDirectionChange(
                        ctx.live.directionActive,
                        ctx.live.directionRawActive,
                        true,
                        ctx.live.triggerActive,
                        ctx.log
                );
            } else if (!active && previousWork) {
                ctx.workSessionDirection.onWorkStopped(ctx.log);
            }
        } else if (!ctx.ioInputConfig.di3Only() && ctx.ioInputConfig.requireDirection()) {
            decisions.evaluateTriggerDecision();
        }
    }
}
