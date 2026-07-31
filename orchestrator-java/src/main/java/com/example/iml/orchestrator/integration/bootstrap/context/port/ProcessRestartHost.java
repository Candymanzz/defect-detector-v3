package com.example.iml.orchestrator.integration.bootstrap.context.port;

/**
 * Порт для {@code CriticalServiceWatchdog}: процессы, пулы, рестарт.
 * Composed of narrower views for ISP: config / collaborators / sink.
 */
public interface ProcessRestartHost
        extends ProcessRestartConfigView, ProcessRestartCollaboratorView, ProcessRestartSink {
}
