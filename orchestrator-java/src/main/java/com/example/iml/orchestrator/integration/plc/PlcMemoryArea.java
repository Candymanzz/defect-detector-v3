package com.example.iml.orchestrator.integration.plc;

/**
 * Области памяти Omron для FINS (битовые и словные коды).
 */
public enum PlcMemoryArea {
  CIO(0x30, 0xB0),
  W(0x31, 0xB1),
  H(0x32, 0xB2),
  DM(0x02, 0x82);

  private final int bitFinsCode;
  private final int wordFinsCode;

  PlcMemoryArea(int bitFinsCode, int wordFinsCode) {
    this.bitFinsCode = bitFinsCode;
    this.wordFinsCode = wordFinsCode;
  }

  /** Код области для битовых операций (Memory Area Write bit). */
  public int finsCode() {
    return bitFinsCode;
  }

  /** Код области для словных операций (Memory Area Read/Write word). */
  public int wordFinsCode() {
    return wordFinsCode;
  }

  public static PlcMemoryArea fromConfig(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("PLC memory area is required");
    }
    return switch (raw.trim().toUpperCase()) {
      case "CIO" -> CIO;
      case "W", "WR", "WORK" -> W;
      case "H", "HR" -> H;
      case "D", "DM", "DMWORD" -> DM;
      default -> throw new IllegalArgumentException("unsupported PLC memory area: " + raw);
    };
  }
}
