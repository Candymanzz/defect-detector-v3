package com.example.iml.orchestrator.integration.plc;

/**
 * Слово DM из register-map: BCD-таймаут (D4400–D4404) или флаг 0/1 ({@code unit: flag}).
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
