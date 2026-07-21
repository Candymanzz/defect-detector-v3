package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.nio.file.Path;
import java.util.Map;

/**
 * Секция {@code plc_fins} из YAML.
 */
public record PlcFinsConfig(
    boolean enabled,
    String host,
    int port,
    int destNode,
    int srcNode,
    int responseTimeoutMs,
    int pulseMs,
    int queueSize,
    Path registerMapPath,
    String visionReadySignal,
    String visionFaultSignal
) {

  public static PlcFinsConfig fromRoot(Map<String, Object> root, Path projectRoot) {
    if (root == null) {
      return disabled(projectRoot);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> plc = (Map<String, Object>) root.get("plc_fins");
    if (plc == null) {
      return disabled(projectRoot);
    }
    boolean enabled = YamlScalars.toBool(plc.get("enabled"), false);
    String host = String.valueOf(plc.getOrDefault("host", "127.0.0.1")).trim();
    int port = Math.max(1, YamlScalars.toInt(plc.get("port"), 9600));
    int destNode = Math.max(0, Math.min(254, YamlScalars.toInt(plc.get("dest_node"), 0)));
    int srcNode = Math.max(0, Math.min(254, YamlScalars.toInt(plc.get("src_node"), 0)));
    int responseTimeoutMs = Math.max(100, YamlScalars.toInt(plc.get("response_timeout_ms"), 1000));
    int pulseMs = Math.max(0, YamlScalars.toInt(plc.get("pulse_ms"), 50));
    int queueSize = Math.max(1, YamlScalars.toInt(plc.get("queue_size"), 64));
    String mapRel = String.valueOf(plc.getOrDefault("register_map_path", "plk/register-map.yaml")).trim();
    Path registerMapPath = projectRoot.resolve(mapRel).normalize();
    String visionReady = String.valueOf(plc.getOrDefault("vision_ready_signal", "vision_ready")).trim();
    String visionFault = String.valueOf(plc.getOrDefault("vision_fault_signal", "vision_fault")).trim();
    return new PlcFinsConfig(
        enabled,
        host,
        port,
        destNode,
        srcNode,
        responseTimeoutMs,
        pulseMs,
        queueSize,
        registerMapPath,
        visionReady,
        visionFault
    );
  }

  private static PlcFinsConfig disabled(Path projectRoot) {
    return new PlcFinsConfig(
        false,
        "127.0.0.1",
        9600,
        0,
        0,
        1000,
        50,
        64,
        projectRoot.resolve("plk/register-map.yaml"),
        "vision_ready",
        "vision_fault"
    );
  }
}
