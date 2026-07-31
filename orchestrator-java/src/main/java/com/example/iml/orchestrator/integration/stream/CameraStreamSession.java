package com.example.iml.orchestrator.integration.stream;

import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-camera client stream session state. */
final class CameraStreamSession {

    private static final int POLL_ERROR_LOG_EVERY = 20;

    final WebSocket connection;
    final int fps;
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicBoolean tickInProgress = new AtomicBoolean(false);
    final AtomicBoolean wsStartedSent = new AtomicBoolean(false);
    final AtomicInteger pollErrors = new AtomicInteger();
    volatile ScheduledFuture<?> future;

    CameraStreamSession(WebSocket connection, int fps) {
        this.connection = connection;
        this.fps = fps;
    }

    void notePollError(Logger log, int cameraId, String reason) {
        int n = pollErrors.incrementAndGet();
        if (n == 1 || n % POLL_ERROR_LOG_EVERY == 0) {
            log.warn("client_stream poll camera={} fail #{}: {}", cameraId, n, reason);
        }
    }
}
