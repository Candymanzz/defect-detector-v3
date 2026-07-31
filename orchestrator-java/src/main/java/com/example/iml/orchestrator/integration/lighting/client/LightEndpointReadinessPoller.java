package com.example.iml.orchestrator.integration.lighting.client;

import org.apache.logging.log4j.Logger;

/**
 * Polls LightServer status until bank is initialized or timeout elapses.
 */
public final class LightEndpointReadinessPoller {

    private final Logger log;
    private final LightServerHttpTransport transport;
    private final boolean enabled;

    public LightEndpointReadinessPoller(Logger log, LightServerHttpTransport transport, boolean enabled) {
        this.log = log;
        this.transport = transport;
        this.enabled = enabled;
    }

    /** Дождаться готовности LightServer ({@code GET status_url}, ethernet bank или COM). */
    public void awaitReady() {
        if (!enabled) {
            return;
        }
        long deadlineNanos = System.nanoTime() + transport.timeout().toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                if (transport.pollBankInitialized()) {
                    return;
                }
            } catch (Exception e) {
                log.debug("light bank status poll: {}", e.getMessage());
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("light bank not ready within {} ms — первый POST on может занять 8–12 s",
                transport.timeout().toMillis());
    }
}
