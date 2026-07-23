package com.example.iml.orchestrator.integration.plc;

/**
 * Один логический сигнал из register-map.
 *
 * @param direction {@code pc_to_plc} — пишем по FINS; {@code plc_to_pc} — только читаем.
 */
public record PlcSignalDefinition(
    String name,
    String description,
    PlcMemoryArea area,
    PlcAddress address,
    Integer bucketGroupId,
    String direction
) {

  public PlcSignalDefinition {
    if (direction == null || direction.isBlank()) {
      direction = "pc_to_plc";
    } else {
      direction = direction.trim().toLowerCase();
    }
  }

  /** Совместимость со старыми вызовами без direction. */
  public PlcSignalDefinition(
      String name,
      String description,
      PlcMemoryArea area,
      PlcAddress address,
      Integer bucketGroupId
  ) {
    this(name, description, area, address, bucketGroupId, "pc_to_plc");
  }

  public boolean writable() {
    return !"plc_to_pc".equals(direction) && !"read".equals(direction);
  }
}
