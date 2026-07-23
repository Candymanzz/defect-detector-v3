package com.example.iml.orchestrator.integration.plc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlcRegisterMapLoaderTest {

  @Test
  void loadsSignalsFromYaml(@TempDir Path tempDir) throws Exception {
    Path map = tempDir.resolve("register-map.yaml");
    Files.writeString(
        map,
        """
        version: 1
        signals:
          - name: vision_fault
            description: fault
            area: CIO
            address: "190.00"
            direction: pc_to_plc
          - name: alarm
            description: Авария
            area: CIO
            address: "140.15"
            direction: plc_to_pc
          - name: reject_line_1
            description: line1
            area: CIO
            address: "140.08"
            bucket_group_id: 0
            direction: pc_to_plc
        """
    );
    PlcRegisterMap loaded = PlcRegisterMapLoader.load(map);
    assertEquals(190, loaded.require("vision_fault").address().word());
    assertEquals(0, loaded.require("vision_fault").address().bit());
    assertEquals(PlcMemoryArea.CIO, loaded.require("vision_fault").area());
    assertTrue(loaded.require("vision_fault").writable());
    assertFalse(loaded.require("alarm").writable());
    assertTrue(loaded.rejectSignalForGroup(0).isPresent());
    assertEquals("reject_line_1", loaded.rejectSignalForGroup(0).get().name());
  }

  @Test
  void loadsProjectRegisterMap() throws Exception {
    Path map = Path.of("..", "plk", "register-map.yaml").toAbsolutePath().normalize();
    if (!Files.isRegularFile(map)) {
      map = Path.of("plk", "register-map.yaml").toAbsolutePath().normalize();
    }
    if (!Files.isRegularFile(map)) {
      return;
    }
    PlcRegisterMap loaded = PlcRegisterMapLoader.load(map);
    assertEquals(PlcMemoryArea.CIO, loaded.require("vision_ready").area());
    assertEquals(140, loaded.require("vision_ready").address().word());
    assertEquals(2, loaded.require("vision_ready").address().bit());
    assertEquals(240, loaded.require("alarm_reset").address().word());
    assertEquals(0, loaded.require("alarm_reset").address().bit());
    assertTrue(loaded.require("alarm_reset").writable());
    assertFalse(loaded.require("robot_working").writable());
  }
}
