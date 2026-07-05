package com.example.iml.orchestrator.integration.trigger.parse;

/** Одно изменение DI от {@code IoInputMonitor}. */
public record IoInputDiChange(int diPort, boolean active) {
}
