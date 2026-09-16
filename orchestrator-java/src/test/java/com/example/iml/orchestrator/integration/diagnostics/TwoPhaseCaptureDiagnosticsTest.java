package com.example.iml.orchestrator.integration.diagnostics;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoPhaseCaptureDiagnosticsTest {

    private static final String LOGGER_NAME = "two-phase-capture-diagnostics-test";

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private Appender appender;
    private LoggerConfig loggerConfig;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        appender = new AbstractAppender(
                "test-two-phase-diag",
                null,
                PatternLayout.createDefaultLayout(),
                false,
                null
        ) {
            @Override
            public void append(LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        loggerConfig = config.getLoggerConfig(LOGGER_NAME);
        if (!loggerConfig.getName().equals(LOGGER_NAME)) {
            loggerConfig = new LoggerConfig(LOGGER_NAME, Level.DEBUG, true);
            config.addLogger(LOGGER_NAME, loggerConfig);
        }
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void detachAppender() {
        if (loggerConfig != null && appender != null) {
            loggerConfig.removeAppender(appender.getName());
            appender.stop();
        }
        messages.clear();
    }

    @Test
    void warnsWhenPhase1TriggerArrivesWhilePhase0WaitFrameInFlight() {
        TwoPhaseCaptureDiagnostics diagnostics =
                new TwoPhaseCaptureDiagnostics(LogManager.getLogger(LOGGER_NAME));

        diagnostics.onTriggerDispatched(0, 77L, 43L, 10);
        diagnostics.onWaitFrameStart(1, 0, 77L, 43L);

        diagnostics.onTriggerDispatched(1, 77L, 44L, 10);

        assertTrue(
                messages.stream().anyMatch(m -> m.contains("event=phase1_trigger_while_phase0_capture")),
                () -> "expected phase1_trigger_while_phase0_capture warn, got: " + messages
        );
    }

    @Test
    void warnsWhenPhase1WaitFrameStartsWhilePhase0StillInFlight() {
        TwoPhaseCaptureDiagnostics diagnostics =
                new TwoPhaseCaptureDiagnostics(LogManager.getLogger(LOGGER_NAME));

        diagnostics.onTriggerDispatched(0, 88L, 50L, 10);
        diagnostics.onWaitFrameStart(3, 0, 88L, 50L);

        diagnostics.onWaitFrameStart(3, 1, 88L, 51L);

        assertTrue(
                messages.stream().anyMatch(m -> m.contains("event=phase1_wait_frame_while_phase0")),
                () -> "expected phase1_wait_frame_while_phase0 warn, got: " + messages
        );
    }

    @Test
    void captureOkIncludesPhaseAndSinceDi3Ms() throws InterruptedException {
        TwoPhaseCaptureDiagnostics diagnostics =
                new TwoPhaseCaptureDiagnostics(LogManager.getLogger(LOGGER_NAME));

        diagnostics.onTriggerDispatched(0, 99L, 60L, 10);
        Thread.sleep(5L);
        diagnostics.onWaitFrameStart(2, 0, 99L, 60L);
        diagnostics.onCaptureOk(2, 0, 99L, 60L, 1001L, 55L, 48L);

        List<String> captureOk = new ArrayList<>();
        for (String message : messages) {
            if (message.contains("event=capture_ok")) {
                captureOk.add(message);
            }
        }
        assertTrue(captureOk.size() == 1, () -> "expected one capture_ok, got: " + captureOk);
        String line = captureOk.get(0);
        assertTrue(line.contains("phase=0"), line);
        assertTrue(line.contains("parent_cycle=99"), line);
        assertTrue(line.contains("raw_seq=60"), line);
        assertTrue(line.contains("since_di3_ms="), line);
    }
}
