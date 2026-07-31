package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;

/**
 * DI2 / direction-port FSM: raw mapping, latch, auto-direction arming.
 */
final class IoInputDi2DirectionPortHandler {

    private final IoInputDiPortContext ctx;
    private final IoInputDiTriggerDecisionSupport decisions;

    IoInputDi2DirectionPortHandler(IoInputDiPortContext ctx, IoInputDiTriggerDecisionSupport decisions) {
        this.ctx = ctx;
        this.decisions = decisions;
    }

    void onDirectionChange(boolean active) {
        boolean previousRaw = ctx.live.directionRawActive;
        if (ctx.usesAutoDirection()) {
            handleAutoDirection(active);
            return;
        }
        if (ctx.ioInputConfig.directionLatchOnWork()) {
            handleLatchOnWork(active, previousRaw);
            return;
        }
        handleMappedDirection(active, previousRaw);
    }

    private void handleAutoDirection(boolean active) {
        ctx.live.directionRawActive = active;
        if (ctx.directionAutoCapture.isDirectionArmed()) {
            return;
        }
        ctx.live.directionActive = ctx.mapDirection(active);
        if (active) {
            ctx.directionAutoCapture.tryArmOnDi2(active, ctx.ioInputConfig.directionInvert());
        }
        if (ctx.directionAutoCapture.isDirectionArmed()) {
            ctx.log.info(
                    "io_input_trigger phase1 done: DI2=1 latched — phase2: DI3 + capture_delay_ms={}",
                    ctx.ioInputConfig.captureDelayMs()
            );
        } else {
            ctx.log.info("io_input_trigger phase1: listening DI2 only, await DI2=1 (DI3 ignored)");
        }
    }

    private void handleLatchOnWork(boolean active, boolean previousRaw) {
        if (!ctx.live.directionInitialized) {
            ctx.live.directionInitialized = true;
            ctx.live.directionRawActive = active;
            ctx.live.directionActive = ctx.mapDirection(active);
            ctx.log.info(
                    "io_input_trigger direction initial raw={} forward={}",
                    active ? 1 : 0,
                    ctx.live.directionActive ? 1 : 0
            );
            ctx.workSessionDirection.onDirectionChange(
                    ctx.live.directionActive, active, ctx.live.workActive, ctx.live.triggerActive, ctx.log);
            return;
        }
        if (previousRaw != active) {
            ctx.log.info(
                    "io_input_trigger direction raw {} -> {} (forward {} -> {})",
                    previousRaw ? 1 : 0,
                    active ? 1 : 0,
                    ctx.mapDirection(previousRaw) ? 1 : 0,
                    ctx.mapDirection(active) ? 1 : 0
            );
            ctx.live.directionRawActive = active;
            ctx.live.directionActive = ctx.mapDirection(active);
            if (!ctx.live.triggerActive) {
                ctx.workSessionDirection.onDirectionChange(
                        ctx.live.directionActive, active, ctx.live.workActive, false, ctx.log);
            }
        }
    }

    private void handleMappedDirection(boolean active, boolean previousRaw) {
        boolean mapped = ctx.mapDirection(active);
        ctx.live.directionRawActive = active;

        // direction_latch: первый DI2=1 фиксирует ход навсегда; дальше DI2 холостой.
        if (ctx.ioInputConfig.directionLatch() && ctx.live.directionLatched) {
            if (previousRaw != active) {
                ctx.live.captureFiredThisDi2Window = false;
                ctx.log.info(
                        "io_input_trigger DI2 idle {} -> {} (направление зафиксировано={})",
                        previousRaw ? 1 : 0,
                        active ? 1 : 0,
                        ctx.live.directionActive ? 1 : 0
                );
            }
            return;
        }

        boolean previousMapped = ctx.live.directionActive;
        if (ctx.live.directionActive != mapped) {
            if (ctx.ioInputConfig.directionInvert()) {
                ctx.log.info(
                        "io_input_trigger direction raw {} -> {} (effective {} -> {})",
                        previousRaw ? 1 : 0,
                        active ? 1 : 0,
                        ctx.live.directionActive ? 1 : 0,
                        mapped ? 1 : 0
                );
            } else {
                ctx.log.info(
                        "io_input_trigger direction {} -> {}",
                        ctx.live.directionActive ? 1 : 0,
                        mapped ? 1 : 0
                );
            }
        }
        ctx.live.directionActive = mapped;
        if (previousMapped != mapped) {
            ctx.live.captureFiredThisDi2Window = false;
        }
        if (ctx.ioInputConfig.directionLatch() && mapped) {
            ctx.live.directionLatched = true;
            if (ctx.manualLineDirection != null) {
                ctx.manualLineDirection.setDirection(ManualLineDirectionService.Direction.FORWARD);
            }
            ctx.log.info(
                    "io_input_trigger direction latched (DI2=1) — дальнейшие смены DI2 холостые для съёмки"
            );
            if (ctx.ioInputConfig.di3Only()) {
                return;
            }
        }
        ctx.directionLatch.onDirectionChange(mapped, ctx.live.triggerActive);
        if (ctx.ioInputConfig.di3Only()) {
            // DI2 — только направление; съёмка только по DI3↑
            return;
        }
        if (ctx.ioInputConfig.requireDirection()) {
            ctx.directionWaiter.onDirectionReadyEvent();
            if (ctx.live.triggerActive) {
                decisions.evaluateTriggerDecision();
            }
        }
    }
}
