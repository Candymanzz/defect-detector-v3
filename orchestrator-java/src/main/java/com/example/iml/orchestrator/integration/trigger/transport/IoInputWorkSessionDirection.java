package com.example.iml.orchestrator.integration.trigger.transport;

import org.apache.logging.log4j.Logger;

/**
 * Направление линии фиксируется один раз после старта работы (DI1↑), дальше DI3 не перепроверяет DI2.
 */
final class IoInputWorkSessionDirection {

    private volatile boolean sessionDirectionKnown;
    private volatile boolean sessionDirectionActive;
    private volatile boolean sessionDirectionRaw;
    private volatile boolean awaitingDirectionAfterWorkStart;

    void onWorkStarted(boolean mappedDirectionActive, boolean rawDirectionActive, boolean triggerActive, Logger log) {
        sessionDirectionKnown = false;
        sessionDirectionActive = false;
        awaitingDirectionAfterWorkStart = true;
        if (triggerActive) {
            log.info("io_input_trigger work started during DI3 pulse — latch DI2 after DI3↓");
            return;
        }
        log.info("io_input_trigger work started (заведение) — latch session direction from DI2");
    }

    void onWorkStopped(Logger log) {
        sessionDirectionKnown = false;
        sessionDirectionActive = false;
        awaitingDirectionAfterWorkStart = false;
        log.info("io_input_trigger work stopped — session direction cleared (re-заведение required after restart/stop)");
    }

    void onDirectionChange(
            boolean mappedDirectionActive,
            boolean rawDirectionActive,
            boolean workActive,
            boolean triggerActive,
            Logger log
    ) {
        if (!workActive || triggerActive || !awaitingDirectionAfterWorkStart || sessionDirectionKnown) {
            return;
        }
        latchSessionDirection(mappedDirectionActive, rawDirectionActive, log);
    }

    boolean allowsCapture(boolean requireWork, boolean workActive, boolean requireDirection) {
        if (requireWork && !workActive) {
            return false;
        }
        if (!requireDirection) {
            return true;
        }
        return sessionDirectionKnown && sessionDirectionActive;
    }

    boolean sessionDirectionKnown() {
        return sessionDirectionKnown;
    }

    boolean sessionDirectionActive() {
        return sessionDirectionActive;
    }

    boolean sessionDirectionRaw() {
        return sessionDirectionRaw;
    }

    private void latchSessionDirection(boolean mapped, boolean raw, Logger log) {
        sessionDirectionActive = mapped;
        sessionDirectionRaw = raw;
        sessionDirectionKnown = true;
        awaitingDirectionAfterWorkStart = false;
        log.info("io_input_trigger session direction latched forward={} raw={}", mapped ? 1 : 0, raw ? 1 : 0);
    }
}
