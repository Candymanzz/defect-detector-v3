package com.example.iml.orchestrator.integration.plc;

/**
 * Состояние битового сигнала для HTTP/UI.
 */
public record PlcSignalState(
    String name,
    String description,
    String area,
    String address,
    Integer bucketGroupId,
    Boolean lastValue,
    String direction,
    boolean writable
) {
}
