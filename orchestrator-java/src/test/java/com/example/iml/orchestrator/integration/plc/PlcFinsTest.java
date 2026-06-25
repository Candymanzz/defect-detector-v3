package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.plc.fins.FinsFrameBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlcAddressTest {

  @Test
  void parsesWordAndBit() {
    PlcAddress address = PlcAddress.parse("140.02");
    assertEquals(140, address.word());
    assertEquals(2, address.bit());
  }
}

class FinsFrameBuilderTest {

  @Test
  void buildsWriteBitFrameForWorkArea() {
    byte[] frame = FinsFrameBuilder.buildWriteBit(
        10,
        20,
        7,
        PlcMemoryArea.W,
        new PlcAddress(0, 4),
        true
    );
    assertEquals(19, frame.length);
    assertEquals((byte) 0x80, frame[0]);
    assertEquals((byte) 10, frame[4]);
    assertEquals((byte) 20, frame[7]);
    assertEquals((byte) 7, frame[9]);
    assertEquals((byte) 0x01, frame[10]);
    assertEquals((byte) 0x02, frame[11]);
    assertEquals((byte) 0x31, frame[12]);
    assertEquals((byte) 0x00, frame[13]);
    assertEquals((byte) 0x00, frame[14]);
    assertEquals((byte) 0x04, frame[15]);
    assertEquals((byte) 0x00, frame[16]);
    assertEquals((byte) 0x01, frame[17]);
    assertEquals((byte) 0x01, frame[18]);
  }

  @Test
  void buildsWriteBitOff() {
    byte[] frame = FinsFrameBuilder.buildWriteBit(
        0,
        0,
        1,
        PlcMemoryArea.W,
        new PlcAddress(0, 5),
        false
    );
    assertArrayEquals(new byte[] {0x00, 0x01}, new byte[] {frame[16], frame[17]});
    assertEquals((byte) 0x00, frame[18]);
  }
}
