package com.example.iml.orchestrator.integration.smoke;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Общий smoke-тест всего оборудования.
 *
 * <p>Запуск:
 * {@code mvn -q test-compile exec:java -Dexec.mainClass=com.example.iml.orchestrator.integration.smoke.HardwareSmokeMain -Dexec.classpathScope=test -Dexec.args=".."}
 *
 * <p>Только часть: {@code -Dexec.args=".. --only=plc,light,cameras,io,light-hw,timing"}
 */
public final class HardwareSmokeMain {

  private static final Logger log = LogManager.getLogger(HardwareSmokeMain.class);

  private HardwareSmokeMain() {
  }

  public static void main(String[] args) throws Exception {
    Path projectRoot = SmokeSupport.projectRoot(args);
    Map<String, Object> root = SmokeSupport.loadConfig(projectRoot);
    List<String> only = SmokeSupport.parseOnlyFilter(args);

    List<SmokeResult> results = new ArrayList<>();
    log.info("hardware smoke start root={} only={}", projectRoot, only.isEmpty() ? "all" : only);

    if (SmokeSupport.shouldRun(only, "cameras")) {
      results.addAll(CameraConnectivitySmokeMain.run(root));
    }
    if (SmokeSupport.shouldRun(only, "light-hw")) {
      results.addAll(LightHardwareSmokeMain.run(root));
    }
    if (SmokeSupport.shouldRun(only, "light")) {
      results.addAll(LightSmokeMain.run(root));
    }
    if (SmokeSupport.shouldRun(only, "io")) {
      results.addAll(IoInputUdpSmokeMain.run(root));
    }
    if (SmokeSupport.shouldRun(only, "plc")) {
      results.addAll(PlcFinsSmokeMain.run(root, projectRoot));
    }
    if (SmokeSupport.shouldRun(only, "timing")) {
      results.addAll(BucketTimingSmokeMain.run(root, projectRoot));
    }

    SmokeSupport.printSummary(results);
    int failures = SmokeSupport.countFailures(results);
    if (failures > 0) {
      log.warn("hardware smoke finished with {} failures", failures);
      System.exit(1);
    }
    log.info("hardware smoke finished OK");
  }
}
