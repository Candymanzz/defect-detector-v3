package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

/**
 * DI3 / trigger-port FSM: rising/falling edges, delayed capture, session hooks.
 */
final class IoInputDi3TriggerPortHandler {

    private final IoInputDiPortContext ctx;
    private final IoInputDiTriggerDecisionSupport decisions;

    IoInputDi3TriggerPortHandler(IoInputDiPortContext ctx, IoInputDiTriggerDecisionSupport decisions) {
        this.ctx = ctx;
        this.decisions = decisions;
    }

    void onTriggerChange(boolean active) {
        if (ctx.usesAutoDirection() && !ctx.directionAutoCapture.isDirectionArmed()) {
            ctx.log.info("io_input_trigger phase1: DI3 ignored until DI2=1 arms direction");
            return;
        }
        // Rising-only photoeye: IoInputMonitor шлёт только UDP DI3=1, без DI3=0.
        // Повторный 3:1 при triggerActive=true — новый импульс, не «залипший HIGH».
        boolean risingEdge = active && !ctx.live.triggerActive;
        boolean risingOnlyRetrigger = active
                && ctx.live.triggerActive
                && ctx.ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING;
        if (risingEdge || risingOnlyRetrigger) {
            onRisingOrRetrigger();
        } else if (!active && ctx.live.triggerActive) {
            onFallingRelease();
        }
        // Rising-only: не держим triggerActive=true — иначе следующий UDP 3:1 молча игнорируется.
        // captureFiredThisDi2Window НЕ сбрасываем — повторный DI3 при DI2=1 остаётся холостым.
        if (active && ctx.ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING) {
            ctx.live.triggerActive = false;
            ctx.live.captureFiredThisPulse = false;
        } else {
            ctx.live.triggerActive = active;
        }
        if (ctx.ioInputConfig.directionLatchOnWork()) {
            if (active && ctx.live.workActive && !ctx.workSessionDirection.sessionDirectionKnown()) {
                ctx.workSessionDirection.onDirectionChange(
                        ctx.live.directionActive, ctx.live.directionRawActive, true, false, ctx.log);
            }
        } else if (ctx.ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING && active) {
            decisions.handleDi3RisingCapture();
        } else if (ctx.ioInputConfig.triggerEdge() == TriggerEdgeMode.FALLING && !active) {
            decisions.evaluateTriggerDecision();
            ctx.directionLatch.onTriggerRelease();
        }
    }

    private void onRisingOrRetrigger() {
        if (ctx.usesAutoDirection()) {
            ctx.live.captureFiredThisPulse = false;
            ctx.directionAutoCapture.onDi3Rising(ctx.live.directionRawActive);
            ctx.capturePublisher.scheduleCaptureAfterDi3Open();
        } else if (ctx.ioInputConfig.directionLatchOnWork()) {
            ctx.log.info(
                    "io_input_trigger DI3 capture edge session_forward={} session_raw={} known={}",
                    ctx.workSessionDirection.sessionDirectionActive() ? 1 : 0,
                    ctx.workSessionDirection.sessionDirectionKnown()
                            ? (ctx.workSessionDirection.sessionDirectionRaw() ? 1 : 0)
                            : -1,
                    ctx.workSessionDirection.sessionDirectionKnown() ? 1 : 0
            );
            ctx.capturePublisher.tryInstantCaptureWithWorkSession();
        } else if (ctx.ioInputConfig.di3Only()) {
            handleDi3OnlyRising();
        } else {
            ctx.log.info("io_input_trigger DI3 capture edge direction={}", ctx.live.directionActive ? 1 : 0);
        }
    }

    private void handleDi3OnlyRising() {
        ctx.live.captureFiredThisPulse = false;
        boolean directionOk = !ctx.ioInputConfig.requireDirection()
                || (ctx.ioInputConfig.directionLatch() ? ctx.live.directionLatched : ctx.live.directionActive);
        if (!directionOk) {
            ctx.log.info(
                    "io_input_trigger skip DI3↑: направление ещё не зафиксировано (жди DI2=1), source={}",
                    ctx.capturePublisher.directionSourceLabel()
            );
        } else if (ctx.live.directionActive && ctx.live.captureFiredThisDi2Window) {
            ctx.log.info(
                    "io_input_trigger skip DI3↑: холостой (уже сняли при DI2=1), source={}",
                    ctx.capturePublisher.directionSourceLabel()
            );
        } else {
            ctx.log.info(
                    "io_input_trigger DI3↑ capture — direction={} latched={} source={}",
                    ctx.capturePublisher.effectiveDirectionWire(),
                    ctx.live.directionLatched ? 1 : 0,
                    ctx.capturePublisher.directionSourceLabel()
            );
            ctx.capturePublisher.fireLineCapture();
        }
    }

    private void onFallingRelease() {
        if (ctx.usesAutoDirection()) {
            ctx.capturePublisher.cancelDelayedCapture();
            if (!ctx.live.captureFiredThisPulse) {
                ctx.log.info("io_input_trigger DI3 pulse end — capture missed (delay {} ms?)",
                        ctx.ioInputConfig.captureDelayMs());
            } else {
                ctx.log.info("io_input_trigger DI3 pulse end — capture done");
            }
        } else if (ctx.ioInputConfig.directionLatchOnWork()) {
            ctx.log.info(
                    "io_input_trigger DI3 release session_forward={}",
                    ctx.workSessionDirection.sessionDirectionActive() ? 1 : 0
            );
            if (ctx.live.workActive && !ctx.workSessionDirection.sessionDirectionKnown()) {
                ctx.workSessionDirection.onDirectionChange(
                        ctx.live.directionActive, ctx.live.directionRawActive, true, false, ctx.log);
            }
        } else if (ctx.ioInputConfig.di3Only()) {
            ctx.capturePublisher.cancelDelayedCapture();
            if (!ctx.live.captureFiredThisPulse) {
                ctx.log.info("io_input_trigger DI3 pulse end — capture missed (delay {} ms?)",
                        ctx.ioInputConfig.captureDelayMs());
            }
        } else {
            ctx.log.info("io_input_trigger DI3 release direction={}", ctx.live.directionActive ? 1 : 0);
        }
        ctx.live.captureFiredThisPulse = false;
        if (ctx.directionWaiter.isWaiting()) {
            ctx.directionWaiter.cancel("DI3 released before direction");
        }
    }
}
