package com.example.iml.orchestrator.integration.plc;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
      List<PlcTimeoutDefinition> timeouts = parseTimeouts(root.get("timeouts"));
      return new PlcRegisterMap(byName, timeouts);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<PlcTimeoutDefinition> parseTimeouts(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      return List.of();
    }
    List<PlcTimeoutDefinition> timeouts = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> entry)) {
        continue;
      }
      timeouts.add(parseTimeout((Map<String, Object>) entry));
    }
    return timeouts;
  }

  private static PlcTimeoutDefinition parseTimeout(Map<String, Object> entry) {
    String name = requiredString(entry, "name");
    String description = String.valueOf(entry.getOrDefault("description", "")).trim();
    PlcMemoryArea area = PlcMemoryArea.fromConfig(String.valueOf(entry.getOrDefault("area", "DM")));
    int wordAddress = parseWordAddress(entry);
    String displayAddress = String.valueOf(entry.getOrDefault("display_address", "D" + wordAddress)).trim();
    if (displayAddress.isBlank()) {
      displayAddress = "D" + wordAddress;
    }
    String encoding = String.valueOf(entry.getOrDefault("encoding", "bcd")).trim().toLowerCase(Locale.ROOT);
    String unit = String.valueOf(entry.getOrDefault("unit", "100ms")).trim();
    return new PlcTimeoutDefinition(name, description, area, wordAddress, displayAddress, encoding, unit);
  }

  private static int parseWordAddress(Map<String, Object> entry) {
    Object addressRaw = entry.get("address");
    if (addressRaw instanceof Number number) {
      return number.intValue();
    }
    if (addressRaw != null) {
      String text = String.valueOf(addressRaw).trim().toUpperCase(Locale.ROOT);
      if (text.startsWith("D")) {
        text = text.substring(1);
      }
      if (!text.isBlank()) {
        return Integer.parseInt(text);
      }
    }
    Object wordRaw = entry.get("word");
    if (wordRaw instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException("PLC timeout missing address/word");
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
    String direction = String.valueOf(entry.getOrDefault("direction", "pc_to_plc")).trim();
    if (direction.isEmpty() || "null".equalsIgnoreCase(direction)) {
      direction = "pc_to_plc";
    }
    return new PlcSignalDefinition(name, description, area, address, bucketGroupId, direction);
  }

  private static String requiredString(Map<String, Object> entry, String key) {
    Object raw = entry.get(key);
    if (raw == null || String.valueOf(raw).isBlank()) {
      throw new IllegalArgumentException("PLC signal missing " + key);
    }
    return String.valueOf(raw).trim();
  }
}
