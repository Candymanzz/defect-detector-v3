package com.example.iml.orchestrator.integration.plc;

import java.util.Map;
import java.util.Optional;

/**
 * Нормализованная карта сигналов техзрения.
 */
public final class PlcRegisterMap {

  private final Map<String, PlcSignalDefinition> byName;

  public PlcRegisterMap(Map<String, PlcSignalDefinition> byName) {
    this.byName = Map.copyOf(byName);
  }

  public Optional<PlcSignalDefinition> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public PlcSignalDefinition require(String name) {
    PlcSignalDefinition signal = byName.get(name);
    if (signal == null) {
      throw new IllegalStateException("PLC signal not configured: " + name);
    }
    return signal;
  }

  public Optional<PlcSignalDefinition> rejectSignalForGroup(int groupId) {
    return byName.values().stream()
        .filter(signal -> signal.bucketGroupId() != null && signal.bucketGroupId() == groupId)
        .findFirst();
  }
}
