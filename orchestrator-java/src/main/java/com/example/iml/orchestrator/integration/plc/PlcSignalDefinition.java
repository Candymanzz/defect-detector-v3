package com.example.iml.orchestrator.integration.plc;

/**
 * Один логический сигнал из register-map.
 */
public record PlcSignalDefinition(
    String name,
    String description,
    PlcMemoryArea area,
    PlcAddress address,
    Integer bucketGroupId
) {
}
