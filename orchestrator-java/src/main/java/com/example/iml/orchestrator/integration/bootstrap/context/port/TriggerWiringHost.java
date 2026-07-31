package com.example.iml.orchestrator.integration.bootstrap.context.port;

/**
 * Порт wiring triggers / line-sync / bucket / interval flash.
 * Composed of narrower views for ISP: config / collaborators / sink.
 */
public interface TriggerWiringHost extends TriggerConfigView, TriggerCollaboratorView, TriggerWiringSink {
}
