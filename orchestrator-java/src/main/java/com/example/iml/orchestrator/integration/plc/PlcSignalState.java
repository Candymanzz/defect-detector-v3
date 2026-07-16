package com.example.iml.orchestrator.integration.plc;

/**
 * Исходящий сигнал PC→PLC для ручной отправки с UI.
 */
public record PlcSignalState(
    String name,
    String description,
    String area,
    String address,
    Integer bucketGroupId,
    Boolean lastValue
) {
}
