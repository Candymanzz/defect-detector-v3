package com.example.iml.orchestrator.integration.plc;

/**
 * Текущее значение таймаута, прочитанное/записанное в ПЛК.
 */
public record PlcTimeoutState(
    String name,
    String description,
    String address,
    int valueUnits,
    int valueMs,
    int rawWord,
    String encoding,
    String unit
) {
}
