package com.example.iml.orchestrator.integration.plc;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Загрузка {@code plk/register-map.yaml}.
 */
public final class PlcRegisterMapLoader {

  private PlcRegisterMapLoader() {
  }

  @SuppressWarnings("unchecked")
  public static PlcRegisterMap load(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("PLC register map not found: " + path.toAbsolutePath());
    }
    try (InputStream in = Files.newInputStream(path)) {
      Object raw = new Yaml().load(in);
      if (!(raw instanceof Map<?, ?> root)) {
        throw new IOException("PLC register map root must be a mapping");
      }
      Object signalsObj = root.get("signals");
      if (!(signalsObj instanceof List<?> signals)) {
        throw new IOException("PLC register map: signals must be a list");
      }
      Map<String, PlcSignalDefinition> byName = new LinkedHashMap<>();
      for (Object item : signals) {
        if (!(item instanceof Map<?, ?> entry)) {
          continue;
        }
        PlcSignalDefinition signal = parseSignal((Map<String, Object>) entry);
        byName.put(signal.name(), signal);
      }
      if (byName.isEmpty()) {
        throw new IOException("PLC register map: no signals parsed");
      }
      return new PlcRegisterMap(byName);
    }
  }

  private static PlcSignalDefinition parseSignal(Map<String, Object> entry) {
    String name = requiredString(entry, "name");
    String description = String.valueOf(entry.getOrDefault("description", ""));
    PlcMemoryArea area = PlcMemoryArea.fromConfig(requiredString(entry, "area"));
    PlcAddress address = PlcAddress.parse(requiredString(entry, "address"));
    Integer bucketGroupId = null;
    Object groupRaw = entry.get("bucket_group_id");
    if (groupRaw instanceof Number number) {
      bucketGroupId = number.intValue();
    }
    return new PlcSignalDefinition(name, description, area, address, bucketGroupId);
  }

  private static String requiredString(Map<String, Object> entry, String key) {
    Object raw = entry.get(key);
    if (raw == null || String.valueOf(raw).isBlank()) {
      throw new IllegalArgumentException("PLC signal missing " + key);
    }
    return String.valueOf(raw).trim();
  }
}
