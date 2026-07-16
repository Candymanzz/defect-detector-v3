package com.example.iml.orchestrator.integration.plc;

/**
 * BCD-кодирование 16-битных слов Omron (как в D4400–D4404, единицы 100 ms).
 */
public final class PlcBcd {

  private PlcBcd() {
  }

  /** Десятичное значение 0…9999 → BCD word. */
  public static int toBcdWord(int decimal) {
    if (decimal < 0 || decimal > 9999) {
      throw new IllegalArgumentException("BCD word out of range 0..9999: " + decimal);
    }
    int result = 0;
    int place = 1;
    int remaining = decimal;
    for (int i = 0; i < 4; i++) {
      int digit = remaining % 10;
      result += digit * place;
      remaining /= 10;
      place *= 16;
    }
    return result;
  }

  /** BCD word → десятичное 0…9999. */
  public static int fromBcdWord(int bcdWord) {
    int value = bcdWord & 0xFFFF;
    int result = 0;
    int place = 1;
    for (int i = 0; i < 4; i++) {
      int digit = value & 0xF;
      if (digit > 9) {
        throw new IllegalArgumentException("invalid BCD nibble in word=0x" + Integer.toHexString(value));
      }
      result += digit * place;
      value >>= 4;
      place *= 10;
    }
    return result;
  }

  public static int unitsToMs(int units100ms) {
    return Math.multiplyExact(units100ms, 100);
  }

  public static int msToUnits(int ms) {
    if (ms < 0) {
      throw new IllegalArgumentException("ms must be >= 0");
    }
    return ms / 100;
  }
}
