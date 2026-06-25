package com.example.iml.orchestrator.integration.plc.fins;

import com.example.iml.orchestrator.integration.plc.PlcAddress;
import com.example.iml.orchestrator.integration.plc.PlcMemoryArea;

/**
 * Сборка FINS/UDP кадров (Memory Area Write, одна битовая ячейка).
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
    byte[] frame = new byte[18];
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
    frame[10] = 0x01;
    frame[11] = 0x02;
    frame[12] = (byte) area.finsCode();
    frame[13] = (byte) ((address.word() >> 8) & 0xFF);
    frame[14] = (byte) (address.word() & 0xFF);
    frame[15] = (byte) address.bit();
    frame[16] = 0x00;
    frame[17] = 0x01;
    // Omron expects data after header for bit write; extend with data byte.
    byte[] withData = new byte[19];
    System.arraycopy(frame, 0, withData, 0, frame.length);
    withData[18] = (byte) (value ? 0x01 : 0x00);
    return withData;
  }
}
