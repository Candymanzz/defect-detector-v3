package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.PreflightContext;

/**
 * Pre-flight: камеры, SHM purge, worker binary, boot config.
 */
public interface PreflightBootstrap {

    /**
     * @return {@code false} при early-exit (нет камер / нет worker binary)
     */
    boolean run(PreflightContext preflight);
}
