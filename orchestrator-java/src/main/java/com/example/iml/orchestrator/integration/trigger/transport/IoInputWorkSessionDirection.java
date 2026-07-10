package com.example.iml.orchestrator.integration.trigger.transport;

import org.apache.logging.log4j.Logger;

/**
 * Направление линии фиксируется один раз после старта работы (DI1↑), дальше DI3 не перепроверяет DI2.
 */
final class IoInputWorkSessionDirection {

    private volatile boolean sessionDirectionKnown;
    private volatile boolean sessionDirectionActive;
    private volatile boolean awaitingDirectionAfterWorkStart;

    void onWorkStarted(boolean currentDirectionActive, Logger log) {
        sessionDirectionKnown = false;
        sessionDirectionActive = false;
        awaitingDirectionAfterWorkStart = true;
        log.info("io_input_trigger work started — await DI2 for session direction");
        if (currentDirectionActive) {
            latchSessionDirection(currentDirectionActive, log);
        }
    }

    void onWorkStopped(Logger log) {
        sessionDirectionKnown = false;
        sessionDirectionActive = false;
        awaitingDirectionAfterWorkStart = false;
        log.info("io_input_trigger work stopped — session direction cleared");
    }

    void onDirectionChange(boolean mappedDirectionActive, boolean workActive, Logger log) {
        if (!workActive || !awaitingDirectionAfterWorkStart || sessionDirectionKnown) {
            return;
        }
        latchSessionDirection(mappedDirectionActive, log);
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

    boolean awaitingDirectionAfterWorkStart() {
        return awaitingDirectionAfterWorkStart;
    }

    private void latchSessionDirection(boolean mapped, Logger log) {
        sessionDirectionActive = mapped;
        sessionDirectionKnown = true;
        awaitingDirectionAfterWorkStart = false;
        log.info("io_input_trigger session direction latched={}", mapped ? 1 : 0);
    }
}
