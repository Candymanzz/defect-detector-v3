package com.example.iml.orchestrator.integration.plc;

/**
 * Адрес бита Omron: слово и номер бита (например W0.04 → word=0, bit=4).
 */
public record PlcAddress(int word, int bit) {

  public PlcAddress {
    if (word < 0) {
      throw new IllegalArgumentException("word must be >= 0");
    }
    if (bit < 0 || bit > 15) {
      throw new IllegalArgumentException("bit must be 0..15");
    }
  }

  public static PlcAddress parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("address is required");
    }
    String trimmed = raw.trim();
    int dot = trimmed.indexOf('.');
    if (dot < 0) {
      return new PlcAddress(Integer.parseInt(trimmed), 0);
    }
    int word = Integer.parseInt(trimmed.substring(0, dot));
    int bit = Integer.parseInt(trimmed.substring(dot + 1));
    return new PlcAddress(word, bit);
  }
}
