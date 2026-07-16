package com.example.iml.orchestrator.integration.plc;

/**
 * Таймаут ПЛК из register-map (D4400–D4404).
 */
public record PlcTimeoutDefinition(
    String name,
    String description,
    PlcMemoryArea area,
    int wordAddress,
    String displayAddress,
    String encoding,
    String unit
) {
}
