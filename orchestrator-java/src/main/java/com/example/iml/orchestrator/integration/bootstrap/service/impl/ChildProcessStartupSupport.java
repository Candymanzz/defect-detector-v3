package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class ChildProcessStartupSupport {
    private ChildProcessStartupSupport() {
    }

    static List<ServiceProcessSupervisor> joinPool(
            Logger log, CompletableFuture<List<ServiceProcessSupervisor>> future, String label
    ) {
        try {
            List<ServiceProcessSupervisor> pool = future.join();
            return pool == null ? List.of() : pool;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("failed to start {} pool in parallel: {}", label, cause.getMessage());
            return List.of();
        }
    }

    static ExternalServiceProcess joinExternal(
            Logger log, CompletableFuture<ExternalServiceProcess> future, String label
    ) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("failed to start {} in parallel: {}", label, cause.getMessage());
            return null;
        }
    }

    static void closeQuietly(ExternalServiceProcess process) {
        if (process != null) {
            process.close();
        }
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }
}
