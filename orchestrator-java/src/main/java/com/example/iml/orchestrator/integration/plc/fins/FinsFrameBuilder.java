package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcAddress;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;

/**
 * Сборка FINS/UDP кадров (Memory Area Write/Read).
 */
public final class FinsFrameBuilder {

  private FinsFrameBuilder() {
  }

  public static byte[] buildWriteBit(
      int destNode,
      int srcNode,
      int serviceId,
      PlcMemoryArea area,
      PlcAddress address,
      boolean value
  ) {
    byte[] withData = new byte[19];
    writeHeader(withData, destNode, srcNode, serviceId);
    withData[10] = 0x01;
    withData[11] = 0x02;
    withData[12] = (byte) area.finsCode();
    withData[13] = (byte) ((address.word() >> 8) & 0xFF);
    withData[14] = (byte) (address.word() & 0xFF);
    withData[15] = (byte) address.bit();
    withData[16] = 0x00;
    withData[17] = 0x01;
    withData[18] = (byte) (value ? 0x01 : 0x00);
    return withData;
  }

  public static byte[] buildWriteWords(
      int destNode,
      int srcNode,
      int serviceId,
      PlcMemoryArea area,
      int startWord,
      int[] words
  ) {
    if (words == null || words.length == 0) {
      throw new IllegalArgumentException("words required");
    }
    if (words.length > 0xFFFF) {
      throw new IllegalArgumentException("too many words");
    }
    byte[] frame = new byte[18 + words.length * 2];
    writeHeader(frame, destNode, srcNode, serviceId);
    frame[10] = 0x01;
    frame[11] = 0x02;
    frame[12] = (byte) area.wordFinsCode();
    frame[13] = (byte) ((startWord >> 8) & 0xFF);
    frame[14] = (byte) (startWord & 0xFF);
    frame[15] = 0x00;
    frame[16] = (byte) ((words.length >> 8) & 0xFF);
    frame[17] = (byte) (words.length & 0xFF);
    for (int i = 0; i < words.length; i++) {
      int word = words[i] & 0xFFFF;
      frame[18 + i * 2] = (byte) ((word >> 8) & 0xFF);
      frame[18 + i * 2 + 1] = (byte) (word & 0xFF);
    }
    return frame;
  }

  public static byte[] buildReadWords(
      int destNode,
      int srcNode,
      int serviceId,
      PlcMemoryArea area,
      int startWord,
      int count
  ) {
    if (count <= 0 || count > 0xFFFF) {
      throw new IllegalArgumentException("invalid word count: " + count);
    }
    byte[] frame = new byte[18];
    writeHeader(frame, destNode, srcNode, serviceId);
    frame[10] = 0x01;
    frame[11] = 0x01;
    frame[12] = (byte) area.wordFinsCode();
    frame[13] = (byte) ((startWord >> 8) & 0xFF);
    frame[14] = (byte) (startWord & 0xFF);
    frame[15] = 0x00;
    frame[16] = (byte) ((count >> 8) & 0xFF);
    frame[17] = (byte) (count & 0xFF);
    return frame;
  }

  public static int[] parseReadWordsData(byte[] response, int length, int expectedCount) {
    int dataOffset = 14;
    int needed = dataOffset + expectedCount * 2;
    if (length < needed) {
      throw new IllegalArgumentException(
          "FINS read response too short len=" + length + " need=" + needed
      );
    }
    int[] words = new int[expectedCount];
    for (int i = 0; i < expectedCount; i++) {
      int hi = response[dataOffset + i * 2] & 0xFF;
      int lo = response[dataOffset + i * 2 + 1] & 0xFF;
      words[i] = (hi << 8) | lo;
    }
    return words;
  }

  public static String toHex(byte[] data, int length) {
    if (data == null || length <= 0) {
      return "";
    }
    int n = Math.min(length, data.length);
    StringBuilder sb = new StringBuilder(n * 2);
    for (int i = 0; i < n; i++) {
      sb.append(String.format("%02X", data[i] & 0xFF));
    }
    return sb.toString();
  }

  private static void writeHeader(byte[] frame, int destNode, int srcNode, int serviceId) {
    frame[0] = (byte) 0x80;
    frame[1] = 0x00;
    frame[2] = 0x02;
    frame[3] = 0x00;
    frame[4] = (byte) destNode;
    frame[5] = 0x00;
    frame[6] = 0x00;
    frame[7] = (byte) srcNode;
    frame[8] = 0x00;
    frame[9] = (byte) (serviceId & 0xFF);
  }
}
