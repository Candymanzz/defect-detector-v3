package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.plc.fins.FinsFrameBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

  @Test
  void buildsReadWordsForDm() {
    byte[] frame = FinsFrameBuilder.buildReadWords(231, 254, 3, PlcMemoryArea.DM, 4400, 5);
    assertEquals(18, frame.length);
    assertEquals((byte) 0x01, frame[10]);
    assertEquals((byte) 0x01, frame[11]);
    assertEquals((byte) 0x82, frame[12]);
    assertEquals((byte) 0x11, frame[13]);
    assertEquals((byte) 0x30, frame[14]);
    assertEquals((byte) 0x00, frame[15]);
    assertEquals((byte) 0x00, frame[16]);
    assertEquals((byte) 0x05, frame[17]);
  }

  @Test
  void buildsWriteWordsForDm() {
    byte[] frame = FinsFrameBuilder.buildWriteWords(
        231,
        254,
        9,
        PlcMemoryArea.DM,
        4400,
        new int[] {PlcBcd.toBcdWord(10), PlcBcd.toBcdWord(20)}
    );
    assertEquals(22, frame.length);
    assertEquals((byte) 0x01, frame[10]);
    assertEquals((byte) 0x02, frame[11]);
    assertEquals((byte) 0x82, frame[12]);
    assertEquals((byte) 0x00, frame[16]);
    assertEquals((byte) 0x02, frame[17]);
    assertEquals((byte) 0x00, frame[18]);
    assertEquals((byte) 0x10, frame[19]);
    assertEquals((byte) 0x00, frame[20]);
    assertEquals((byte) 0x20, frame[21]);
  }

  @Test
  void parsesReadWordsData() {
    byte[] response = new byte[18];
    response[14] = 0x00;
    response[15] = 0x10;
    response[16] = 0x00;
    response[17] = 0x25;
    int[] words = FinsFrameBuilder.parseReadWordsData(response, response.length, 2);
    assertArrayEquals(new int[] {0x0010, 0x0025}, words);
  }
}

class PlcBcdTest {

  @Test
  void roundTripsCommonValues() {
    assertEquals(0x0010, PlcBcd.toBcdWord(10));
    assertEquals(10, PlcBcd.fromBcdWord(0x0010));
    assertEquals(0x1234, PlcBcd.toBcdWord(1234));
    assertEquals(1234, PlcBcd.fromBcdWord(0x1234));
  }

  @Test
  void rejectsInvalidBcd() {
    assertThrows(IllegalArgumentException.class, () -> PlcBcd.fromBcdWord(0x001A));
    assertThrows(IllegalArgumentException.class, () -> PlcBcd.toBcdWord(10000));
  }

  @Test
  void convertsUnitsToMs() {
    assertEquals(1500, PlcBcd.unitsToMs(15));
    assertEquals(15, PlcBcd.msToUnits(1599));
  }
}
