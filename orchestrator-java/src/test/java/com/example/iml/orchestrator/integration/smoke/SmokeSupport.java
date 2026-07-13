package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.config.YamlFileConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SmokeSupport {

  private static final Logger log = LogManager.getLogger(SmokeSupport.class);

  private SmokeSupport() {
  }

  public static Path projectRoot(String[] args) {
    return Path.of(args.length > 0 ? args[0] : "../").toAbsolutePath().normalize();
  }

  public static Map<String, Object> loadConfig(Path projectRoot) throws Exception {
    return new YamlFileConfigLoader().load(projectRoot.resolve("config/config.yaml"));
  }

  public static boolean pingHost(String host, int timeoutMs) {
    try {
      InetAddress address = InetAddress.getByName(host);
      return address.isReachable(Math.max(500, timeoutMs));
    } catch (Exception e) {
      log.debug("ping {} failed: {}", host, e.getMessage());
      return false;
    }
  }

  public static void sleep(long ms) throws InterruptedException {
    if (ms > 0) {
      Thread.sleep(ms);
    }
  }

  public static void logStep(String label) {
    log.info(">>> {}", label);
  }

  public static int countFailures(List<SmokeResult> results) {
    int failures = 0;
    for (SmokeResult result : results) {
      if (!result.passed()) {
        failures++;
      }
    }
    return failures;
  }

  public static void printSummary(List<SmokeResult> results) {
    int failures = countFailures(results);
    log.info("--- smoke summary: {} steps, {} failed ---", results.size(), failures);
    for (SmokeResult result : results) {
      String status = result.passed() ? "OK" : "FAIL";
      log.info("[{}] {} / {} — {}", status, result.component(), result.step(), result.detail());
    }
  }

  public static List<String> parseOnlyFilter(String[] args) {
    List<String> only = new ArrayList<>();
    for (int i = 1; i < args.length; i++) {
      String arg = args[i].trim().toLowerCase();
      if (arg.startsWith("--only=")) {
        for (String part : arg.substring("--only=".length()).split(",")) {
          String item = part.trim();
          if (!item.isEmpty()) {
            only.add(item);
          }
        }
      }
    }
    return only;
  }

  public static boolean shouldRun(List<String> only, String name) {
    return only.isEmpty() || only.contains(name);
  }
}
