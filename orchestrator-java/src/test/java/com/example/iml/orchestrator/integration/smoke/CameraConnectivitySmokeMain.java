package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ping всех включённых камер из config/blocks/10-cameras.yaml. */
public final class CameraConnectivitySmokeMain {

  private static final Logger log = LogManager.getLogger(CameraConnectivitySmokeMain.class);
  private static final int PING_TIMEOUT_MS = 1500;

  private CameraConnectivitySmokeMain() {
  }

  @SuppressWarnings("unchecked")
  public static List<SmokeResult> run(Map<String, Object> root) {
    List<SmokeResult> results = new ArrayList<>();
    Object raw = root.get("cameras");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      results.add(SmokeResult.skip("cameras", "config", "no cameras in config"));
      return results;
    }

    int reachable = 0;
    int enabled = 0;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> entry)) {
        continue;
      }
      Map<String, Object> cam = (Map<String, Object>) entry;
      if (!YamlScalars.toBool(cam.get("enabled"), true)) {
        continue;
      }
      int id = YamlScalars.toInt(cam.get("id"), -1);
      String ip = String.valueOf(cam.getOrDefault("ip", "")).trim();
      if (id < 0 || ip.isEmpty()) {
        results.add(SmokeResult.fail("cameras", "camera-" + id, "missing id or ip"));
        continue;
      }
      enabled++;
      SmokeSupport.logStep("ping camera-" + id + " " + ip);
      boolean ok = SmokeSupport.pingHost(ip, PING_TIMEOUT_MS);
      if (ok) {
        reachable++;
        results.add(SmokeResult.ok("cameras", "camera-" + id, ip + " reachable"));
      } else {
        results.add(SmokeResult.fail("cameras", "camera-" + id, ip + " unreachable"));
      }
    }

    log.info("cameras reachable {}/{}", reachable, enabled);
    return results;
  }
}
