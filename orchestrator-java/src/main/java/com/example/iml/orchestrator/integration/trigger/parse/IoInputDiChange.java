package com.example.iml.orchestrator.integration.trigger.parse;

/** Одно изменение DI от {@code IO source}. */
public record IoInputDiChange(int diPort, boolean active) {
}
