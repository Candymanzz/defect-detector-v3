package com.example.iml.orchestrator.integration.bootstrap.context.port;

/**
 * Порт fan-out / health gate / stop-signal.
 * Composed of narrower views for ISP: config / collaborators / sink.
 */
public interface FanOutHealthHost
        extends FanOutHealthConfigView, FanOutHealthCollaboratorView, FanOutHealthSink {
}
