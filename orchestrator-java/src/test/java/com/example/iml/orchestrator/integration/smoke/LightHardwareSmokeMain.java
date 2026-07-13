package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ping Ethernet-контроллеров подсветки из config/blocks/51-light-hardware.yaml. */
public final class LightHardwareSmokeMain {

  private static final Logger log = LogManager.getLogger(LightHardwareSmokeMain.class);
  private static final int PING_TIMEOUT_MS = 1500;

  private LightHardwareSmokeMain() {
  }

  @SuppressWarnings("unchecked")
  public static List<SmokeResult> run(Map<String, Object> root) {
    List<SmokeResult> results = new ArrayList<>();
    Object sectionObj = root.get("light_hardware");
    if (!(sectionObj instanceof Map<?, ?> section)) {
      results.add(SmokeResult.skip("light-hw", "config", "light_hardware section missing"));
      return results;
    }

    Object devicesObj = section.get("devices");
    if (!(devicesObj instanceof List<?> devices) || devices.isEmpty()) {
      results.add(SmokeResult.fail("light-hw", "config", "no devices configured"));
      return results;
    }

    int ethernetReachable = 0;
    int ethernetTotal = 0;
    for (Object item : devices) {
      if (!(item instanceof Map<?, ?> entry)) {
        continue;
      }
      Map<String, Object> device = (Map<String, Object>) entry;
      if (!YamlScalars.toBool(device.get("enabled"), true)) {
        continue;
      }
      String id = String.valueOf(device.getOrDefault("id", "unknown"));
      String type = String.valueOf(device.getOrDefault("type", "")).trim().toLowerCase();
      if ("com".equals(type)) {
        String comPort = String.valueOf(device.getOrDefault("com_port", "")).trim();
        SmokeSupport.logStep("COM device " + id + " port=" + comPort);
        results.add(SmokeResult.skip("light-hw", id, "COM " + comPort + " — проверка через LightServer"));
        continue;
      }
      if (!"ethernet".equals(type)) {
        results.add(SmokeResult.skip("light-hw", id, "unknown type " + type));
        continue;
      }
      String ip = String.valueOf(device.getOrDefault("ip", "")).trim();
      if (ip.isEmpty()) {
        results.add(SmokeResult.fail("light-hw", id, "ethernet device without ip"));
        continue;
      }
      ethernetTotal++;
      SmokeSupport.logStep("ping " + id + " " + ip);
      boolean ok = SmokeSupport.pingHost(ip, PING_TIMEOUT_MS);
      if (ok) {
        ethernetReachable++;
        results.add(SmokeResult.ok("light-hw", id, ip + " reachable"));
      } else {
        results.add(SmokeResult.fail("light-hw", id, ip + " unreachable"));
      }
    }

    log.info("light hardware ethernet reachable {}/{}", ethernetReachable, ethernetTotal);
    return results;
  }
}
