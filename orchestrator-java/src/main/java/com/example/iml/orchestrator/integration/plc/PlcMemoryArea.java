package com.example.iml.orchestrator.integration.plc;

/**
 * Области памяти Omron для FINS (битовые).
 */
public enum PlcMemoryArea {
  CIO(0x30),
  W(0x31),
  H(0x32);

  private final int finsCode;

  PlcMemoryArea(int finsCode) {
    this.finsCode = finsCode;
  }

  public int finsCode() {
    return finsCode;
  }

  public static PlcMemoryArea fromConfig(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("PLC memory area is required");
    }
    return switch (raw.trim().toUpperCase()) {
      case "CIO" -> CIO;
      case "W", "WR", "WORK" -> W;
      case "H", "HR" -> H;
      default -> throw new IllegalArgumentException("unsupported PLC memory area: " + raw);
    };
  }
}
