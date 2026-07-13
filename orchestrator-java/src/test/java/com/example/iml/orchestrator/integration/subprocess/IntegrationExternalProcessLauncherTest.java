package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationExternalProcessLauncherTest {

  @TempDir
  Path projectRoot;

  @Test
  void parsesAutostartBlock() {
    var launcher = new IntegrationExternalProcessLauncher(LogManager.getLogger("test"));
    Map<String, Object> integration = Map.of(
        "frontend_autostart",
        Map.of("enabled", true, "startup_delay_ms", 1500, "working_dir", "front-end")
    );

    var settings = launcher.parseAutostart(integration, "frontend_autostart", projectRoot, ".");

    assertTrue(settings.enabled());
    assertEquals(1500, settings.startupDelayMs());
    assertEquals(projectRoot.resolve("front-end").normalize(), settings.workingDir());
  }

  @Test
  void missingAutostartBlockIsDisabled() {
    var launcher = new IntegrationExternalProcessLauncher(LogManager.getLogger("test"));
    var settings = launcher.parseAutostart(Map.of(), "io_input_monitor_autostart", projectRoot, ".");

    assertFalse(settings.enabled());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void prepareCommandResolvesNpmOnWindows() {
    List<String> command = IntegrationExternalProcessLauncher.prepareCommand(
            List.of("npm", "run", "dev"), true);
    assertTrue(command.get(0).toLowerCase().endsWith("npm.cmd"));
  }

  @Test
  void prepareCommandLeavesLinuxNpmUntouched() {
    List<String> command = IntegrationExternalProcessLauncher.prepareCommand(
            List.of("npm", "run", "dev"), false);
    assertEquals("npm", command.get(0));
  }
}
