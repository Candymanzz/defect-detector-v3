package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.lighting.LightServerV3Http;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    LightServersConfig cfg = LightServersConfig.fromRootYaml(root);
    if (!cfg.enabled()) {
      results.add(SmokeResult.skip("light", "config", "light_servers.enabled=false"));
      return results;
    }

    Duration timeout = Duration.ofMillis(Math.max(100, cfg.timeoutMs()));
    HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
    String statusUrl = LightServerV3Http.normalizeBaseUrl(cfg.upstreamBaseUrl()) + LightServerV3Http.PATH_COM_LIGHT;
    SmokeSupport.logStep("GET status " + statusUrl);
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(statusUrl))
          .timeout(timeout)
          .GET()
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 == 2) {
        results.add(SmokeResult.ok("light", "status", "HTTP " + response.statusCode() + " " + truncate(response.body())));
      } else {
        results.add(SmokeResult.fail("light", "status", "HTTP " + response.statusCode()));
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

  private static int[] sampleCameraIds(LightServersConfig cfg) {
    if (cfg.cameras().isEmpty()) {
      return new int[] {0, 8};
    }
    List<Integer> ids = cfg.cameras().stream()
        .map(LightServersConfig.CameraFlashSpec::cameraId)
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
