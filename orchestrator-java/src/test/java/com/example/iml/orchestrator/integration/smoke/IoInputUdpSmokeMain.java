package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChangeParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Имитация IoInputMonitor: UDP JSON на порт оркестратора (inspection_trigger.udp.bind_port).
 * Оркестратор должен быть запущен, иначе пакеты уйдут в никуда (шаг send всё равно OK).
 */
public final class IoInputUdpSmokeMain {

  private static final Logger log = LogManager.getLogger(IoInputUdpSmokeMain.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private IoInputUdpSmokeMain() {
  }

  @SuppressWarnings("unchecked")
  public static List<SmokeResult> run(Map<String, Object> root) throws Exception {
    List<SmokeResult> results = new ArrayList<>();
    Object integrationObj = root.get("integration");
    Map<String, Object> integration = integrationObj instanceof Map<?, ?> m
        ? (Map<String, Object>) m
        : Map.of();
    InspectionTriggerConfig triggerCfg = InspectionTriggerConfig.parse(integration);
    if (!triggerCfg.udp().enabled()) {
      results.add(SmokeResult.skip("io", "config", "inspection_trigger.udp disabled"));
      return results;
    }

    String host = resolvePublishHost(root);
    int port = triggerCfg.udp().bindPort();
    String format = resolvePublishFormat(root, triggerCfg);

    results.add(SmokeResult.ok("io", "target", host + ":" + port + " format=" + format));

    sendDi(results, host, port, format, 1, true, "DI1 work ON");
    SmokeSupport.sleep(200);
    sendDi(results, host, port, format, 2, false, "DI2 direction OFF");
    SmokeSupport.sleep(200);
    sendDi(results, host, port, format, 3, false, "DI3 idle");
    SmokeSupport.sleep(200);
    sendDi(results, host, port, format, 3, true, "DI3 trigger rising");
    SmokeSupport.sleep(300);
    sendDi(results, host, port, format, 3, false, "DI3 trigger falling");

    log.info("io udp smoke sent 5 packets to {}:{}", host, port);
    return results;
  }

  @SuppressWarnings("unchecked")
  private static String resolvePublishHost(Map<String, Object> root) {
    Object ioObj = root.get("io_input");
    if (ioObj instanceof Map<?, ?> ioMap) {
      @SuppressWarnings("unchecked")
      Map<String, Object> io = (Map<String, Object>) ioMap;
      Object publishObj = io.get("publish");
      if (publishObj instanceof Map<?, ?> publish) {
        Object udpObj = publish.get("udp");
        if (udpObj instanceof Map<?, ?> udpMap) {
          @SuppressWarnings("unchecked")
          Map<String, Object> udp = (Map<String, Object>) udpMap;
          String host = String.valueOf(udp.getOrDefault("host", "127.0.0.1")).trim();
          if (!host.isEmpty()) {
            return host;
          }
        }
      }
    }
    return "127.0.0.1";
  }

  @SuppressWarnings("unchecked")
  private static String resolvePublishFormat(Map<String, Object> root, InspectionTriggerConfig triggerCfg) {
    Object ioObj = root.get("io_input");
    if (ioObj instanceof Map<?, ?> ioMap) {
      @SuppressWarnings("unchecked")
      Map<String, Object> io = (Map<String, Object>) ioMap;
      Object publishObj = io.get("publish");
      if (publishObj instanceof Map<?, ?> publish) {
        Object udpObj = publish.get("udp");
        if (udpObj instanceof Map<?, ?> udpMap) {
          @SuppressWarnings("unchecked")
          Map<String, Object> udp = (Map<String, Object>) udpMap;
          if (udp.get("format") != null) {
            return String.valueOf(udp.get("format")).trim().toLowerCase();
          }
        }
      }
      String payloadFormat = String.valueOf(io.getOrDefault("payload_format", "")).trim();
      if (!payloadFormat.isEmpty()) {
        return payloadFormat.toLowerCase();
      }
    }
    return triggerCfg.ioInput().payloadFormat();
  }

  private static void sendDi(
      List<SmokeResult> results,
      String host,
      int port,
      String format,
      int di,
      boolean value,
      String label
  ) throws Exception {
    byte[] payload = buildPayload(format, di, value);
    SmokeSupport.logStep(label + " -> " + host + ":" + port);
    IoInputDiChangeParser.parse(payload, payload.length, format)
        .ifPresentOrElse(
            parsed -> {
              if (parsed.diPort() == di && parsed.active() == value) {
                results.add(SmokeResult.ok("io", label, "parsed di=" + di + " value=" + value));
              } else {
                results.add(SmokeResult.fail("io", label, "parser mismatch"));
              }
            },
            () -> results.add(SmokeResult.fail("io", label, "payload does not parse"))
        );

    try (DatagramSocket socket = new DatagramSocket()) {
      InetAddress address = InetAddress.getByName(host);
      DatagramPacket packet = new DatagramPacket(payload, payload.length, address, port);
      socket.send(packet);
      results.add(SmokeResult.ok("io", label + " send", new String(payload, StandardCharsets.UTF_8)));
    } catch (Exception e) {
      results.add(SmokeResult.fail("io", label + " send", e.getMessage()));
    }
  }

  private static byte[] buildPayload(String format, int di, boolean value) throws Exception {
    return switch (format) {
      case "text_di" -> (di + ":" + (value ? 1 : 0)).getBytes(StandardCharsets.UTF_8);
      case "byte_di" -> new byte[] {(byte) di, (byte) (value ? 1 : 0)};
      case "byte", "text" -> new byte[] {(byte) (value ? 1 : 0)};
      default -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("di", di);
        body.put("value", value ? 1 : 0);
        body.put("ts_ms", System.currentTimeMillis());
        yield MAPPER.writeValueAsBytes(body);
      }
    };
  }
}
