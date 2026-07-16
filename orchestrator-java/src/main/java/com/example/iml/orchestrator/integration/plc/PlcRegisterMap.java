package com.example.iml.orchestrator.integration.plc;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Нормализованная карта сигналов техзрения и таймаутов ПЛК.
 */
public final class PlcRegisterMap {

  private final Map<String, PlcSignalDefinition> byName;
  private final List<PlcTimeoutDefinition> timeouts;

  public PlcRegisterMap(Map<String, PlcSignalDefinition> byName) {
    this(byName, List.of());
  }

  public PlcRegisterMap(Map<String, PlcSignalDefinition> byName, List<PlcTimeoutDefinition> timeouts) {
    this.byName = Map.copyOf(byName);
    this.timeouts = List.copyOf(timeouts);
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

  public List<PlcTimeoutDefinition> timeouts() {
    return timeouts;
  }

  public Optional<PlcTimeoutDefinition> findTimeout(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    String normalized = key.trim();
    for (PlcTimeoutDefinition timeout : timeouts) {
      if (timeout.name().equalsIgnoreCase(normalized)
          || timeout.displayAddress().equalsIgnoreCase(normalized)
          || ("D" + timeout.wordAddress()).equalsIgnoreCase(normalized)) {
        return Optional.of(timeout);
      }
    }
    return Optional.empty();
  }

  public Map<String, PlcTimeoutDefinition> timeoutsByDisplayAddress() {
    Map<String, PlcTimeoutDefinition> map = new LinkedHashMap<>();
    for (PlcTimeoutDefinition timeout : timeouts) {
      map.put(timeout.displayAddress(), timeout);
    }
    return map;
  }

  public Collection<PlcSignalDefinition> signals() {
    return byName.values();
  }
}
