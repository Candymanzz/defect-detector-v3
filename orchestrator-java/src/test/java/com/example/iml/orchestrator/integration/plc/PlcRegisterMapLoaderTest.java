package com.example.iml.orchestrator.integration.plc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            area: W
            address: "0.05"
            direction: pc_to_plc
          - name: reject_line_1
            description: line1
            area: W
            address: "0.06"
            bucket_group_id: 0
        """
    );
    PlcRegisterMap loaded = PlcRegisterMapLoader.load(map);
    assertEquals(0, loaded.require("vision_fault").address().word());
    assertEquals(5, loaded.require("vision_fault").address().bit());
    assertTrue(loaded.rejectSignalForGroup(0).isPresent());
    assertEquals("reject_line_1", loaded.rejectSignalForGroup(0).get().name());
  }
}
