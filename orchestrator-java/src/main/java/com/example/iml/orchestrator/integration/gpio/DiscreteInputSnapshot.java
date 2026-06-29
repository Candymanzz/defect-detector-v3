package com.example.iml.orchestrator.integration.gpio;

/** Снимок трёх дискретных входов линии. */
public record DiscreteInputSnapshot(boolean work, boolean direction, boolean trigger) {
}
