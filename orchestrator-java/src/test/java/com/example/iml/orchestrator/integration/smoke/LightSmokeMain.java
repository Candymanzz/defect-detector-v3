package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.lighting.LightServerV3Http;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP smoke-тест LightServer.v3: статус и вспышки по маршрутам камер. */
public final class LightSmokeMain {

  private static final Logger log = LogManager.getLogger(LightSmokeMain.class);

  private LightSmokeMain() {
  }

  public static List<SmokeResult> run(Map<String, Object> root) throws Exception {
    List<SmokeResult> results = new ArrayList<>();
    var cfg = com.example.iml.orchestrator.integration.lighting.LightServersConfig.fromRootYaml(root);
    if (!cfg.enabled()) {
      results.add(SmokeResult.skip("light", "config", "light_servers.enabled=false"));
      return results;
    }

    var upstream = new com.example.iml.orchestrator.integration.lighting.LightUpstreamClient(cfg);
    SmokeSupport.logStep("GET status " + cfg.upstreamBaseUrl() + LightServerV3Http.PATH_COM_LIGHT);
    try {
      var status = upstream.get(LightServerV3Http.PATH_COM_LIGHT);
      if (status.ok()) {
        results.add(SmokeResult.ok("light", "status", "HTTP " + status.statusCode() + " " + truncate(status.body())));
      } else {
        results.add(SmokeResult.fail("light", "status", "HTTP " + status.statusCode()));
        return results;
      }
    } catch (Exception e) {
      results.add(SmokeResult.fail("light", "status", "LightServer unreachable: " + e.getMessage()));
      return results;
    }

    var client = new com.example.iml.orchestrator.integration.lighting.LightTriggerClient(cfg);
    try {
      SmokeSupport.logStep("await COM bank initialized");
      client.awaitEndpointsReady();
      results.add(SmokeResult.ok("light", "init", "endpoints ready or timeout logged"));

      for (int cameraId : sampleCameraIds(cfg)) {
        String label = "flash camera-" + cameraId;
        SmokeSupport.logStep(label);
        boolean ok = client.lightOn(cameraId, 1L, "smoke");
        if (ok) {
          results.add(SmokeResult.ok("light", label, "brightness route OK"));
        } else {
          results.add(SmokeResult.fail("light", label, "lightOn returned false"));
        }
        SmokeSupport.sleep(400);
      }

      SmokeSupport.logStep("force all off");
      client.forceAllOff();
      results.add(SmokeResult.ok("light", "off", "forceAllOff sent"));
    } finally {
      client.shutdown();
    }

    log.info("light smoke finished, cameras configured={}", cfg.cameras().size());
    return results;
  }

  private static int[] sampleCameraIds(
      com.example.iml.orchestrator.integration.lighting.LightServersConfig cfg
  ) {
    if (cfg.cameras().isEmpty()) {
      return new int[] {0, 8};
    }
    List<Integer> ids = cfg.cameras().stream()
        .map(com.example.iml.orchestrator.integration.lighting.LightServersConfig.CameraFlashSpec::cameraId)
        .sorted()
        .toList();
    List<Integer> sample = new ArrayList<>();
    sample.add(ids.get(0));
    if (ids.size() > 1) {
      sample.add(ids.get(ids.size() / 2));
    }
    Integer single = ids.stream().filter(id -> id >= 8).findFirst().orElse(null);
    if (single != null && !sample.contains(single)) {
      sample.add(single);
    }
    return sample.stream().mapToInt(Integer::intValue).toArray();
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    String trimmed = body.replace('\n', ' ').trim();
    return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "...";
  }
}
