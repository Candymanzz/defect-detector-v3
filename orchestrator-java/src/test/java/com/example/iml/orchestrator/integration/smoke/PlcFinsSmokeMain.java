package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.PlcFinsConfig;
import com.example.iml.orchestrator.integration.plc.PlcFinsPublisher;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMap;
import com.example.iml.orchestrator.integration.plc.PlcRegisterMapLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** FINS smoke-тест: сигналы техзрения на ПЛК Omron. */
public final class PlcFinsSmokeMain {

  private static final Logger log = LogManager.getLogger(PlcFinsSmokeMain.class);

  private PlcFinsSmokeMain() {
  }

  public static void main(String[] args) throws Exception {
    Path projectRoot = SmokeSupport.projectRoot(args);
    Map<String, Object> root = SmokeSupport.loadConfig(projectRoot);
    List<SmokeResult> results = run(root, projectRoot);
    SmokeSupport.printSummary(results);
    if (SmokeSupport.countFailures(results) > 0) {
      System.exit(1);
    }
  }

  public static List<SmokeResult> run(Map<String, Object> root, Path projectRoot) throws Exception {
    List<SmokeResult> results = new ArrayList<>();
    PlcFinsConfig cfg = PlcFinsConfig.fromRoot(root, projectRoot);
    if (!cfg.enabled()) {
      results.add(SmokeResult.skip("plc", "config", "plc_fins.enabled=false"));
      return results;
    }

    PlcRegisterMap map = PlcRegisterMapLoader.load(cfg.registerMapPath());
    log.info(
        "PLC smoke host={}:{} dest_node={} src_node={} map={}",
        cfg.host(),
        cfg.port(),
        cfg.destNode(),
        cfg.srcNode(),
        cfg.registerMapPath()
    );
    results.add(SmokeResult.ok("plc", "config", cfg.host() + ":" + cfg.port()));

    try (PlcFinsPublisher publisher = PlcFinsPublisher.create(log, cfg, map)) {
      results.add(runStep("plc", "vision_ready ON", () -> publisher.setVisionReady(true)));
      SmokeSupport.sleep(1500);

      results.add(runStep("plc", "reject_line_1 pulse", () ->
          publisher.publishBucket(new BucketFanOutResult(0, 1L, false, List.of(0, 1, 2, 3, 4), Map.of()))));
      SmokeSupport.sleep(cfg.pulseMs() + 500);

      results.add(runStep("plc", "reject_line_2 pulse", () ->
          publisher.publishBucket(new BucketFanOutResult(1, 2L, false, List.of(5, 6, 7, 8, 9), Map.of()))));
      SmokeSupport.sleep(cfg.pulseMs() + 500);

      results.add(runStep("plc", "vision_fault ON", () -> publisher.setVisionFault(true)));
      SmokeSupport.sleep(1500);

      results.add(runStep("plc", "vision_fault OFF", () -> publisher.setVisionFault(false)));
      SmokeSupport.sleep(500);

      results.add(runStep("plc", "vision_ready OFF", () -> publisher.setVisionReady(false)));
      SmokeSupport.sleep(500);
    } catch (Exception e) {
      results.add(SmokeResult.fail("plc", "fins", e.getMessage()));
    }

    log.info("PLC smoke finished");
    return results;
  }

  private static SmokeResult runStep(String component, String step, Runnable action) {
    try {
      SmokeSupport.logStep(step);
      action.run();
      SmokeSupport.sleep(300);
      return SmokeResult.ok(component, step, "FINS write OK");
    } catch (Exception e) {
      return SmokeResult.fail(component, step, e.getMessage());
    }
  }
}
