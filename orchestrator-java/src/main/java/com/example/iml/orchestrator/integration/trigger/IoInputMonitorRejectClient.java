package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Техзрение → IoInputMonitor DO → физические входы ПЛК (X4 ready, X5 fault, X6/X7 брак).
 * FINS остаётся только для таймаутов D4400–D4404. CIO 240.15 не используется.
 */
public final class IoInputMonitorRejectClient implements AutoCloseable {

    private final Logger log;
    private final boolean enabled;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "io-plc-discrete");
        t.setDaemon(true);
        return t;
    });

    public IoInputMonitorRejectClient(Logger log, boolean enabled, String baseUrl) {
        this.log = log;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    @SuppressWarnings("unchecked")
    public static IoInputMonitorRejectClient fromIntegration(Logger log, Map<String, Object> integration) {
        boolean enabled = true;
        String url = "http://127.0.0.1:9101";
        if (integration != null) {
            Object raw = integration.get("io_input_monitor_reject");
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> section = (Map<String, Object>) map;
                Object en = section.get("enabled");
                if (en instanceof Boolean b) {
                    enabled = b;
                } else if (en != null) {
                    enabled = Boolean.parseBoolean(String.valueOf(en).trim());
                }
                Object urlRaw = section.get("url");
                if (urlRaw != null) {
                    String configured = String.valueOf(urlRaw).trim();
                    if (!configured.isEmpty()) {
                        url = configured;
                    }
                }
            }
            Object legacyUrl = integration.get("io_input_monitor_direction_url");
            if ((url == null || url.isBlank()) && legacyUrl != null) {
                url = String.valueOf(legacyUrl).trim();
            }
        }
        return new IoInputMonitorRejectClient(log, enabled, url);
    }

    public boolean isEnabled() {
        return enabled && !baseUrl.isEmpty();
    }

    /**
     * На fail — импульс DO линии; на pass — no-op (дискретный вход сам отпускается после импульса).
     */
    public void publishBucket(BucketFanOutResult result) {
        if (!isEnabled() || result == null || result.overallPass()) {
            return;
        }
        int groupId = result.groupId();
        if (groupId != 0 && groupId != 1) {
            log.warn("io_input_monitor reject skip: unsupported group_id={}", groupId);
            return;
        }
        int line = groupId + 1;
        executor.execute(() -> postJson("/reject", "{\"line\":" + line + "}", "reject line=" + line + " seq=" + result.triggerSequence()));
    }

    public void setVisionReady(boolean ready) {
        if (!isEnabled()) {
            return;
        }
        executor.execute(() -> postJson(
                "/vision-ready",
                "{\"value\":" + ready + "}",
                "vision_ready=" + ready
        ));
    }

    public void setVisionFault(boolean fault) {
        if (!isEnabled()) {
            return;
        }
        executor.execute(() -> postJson(
                "/vision-fault",
                "{\"value\":" + fault + "}",
                "vision_fault=" + fault
        ));
    }

    public void pulseSignal(String signalName) {
        if (!isEnabled() || signalName == null) {
            return;
        }
        String name = signalName.trim().toLowerCase();
        switch (name) {
            case "reject_line_1" -> postJson("/reject", "{\"line\":1}", "reject_line_1");
            case "reject_line_2" -> postJson("/reject", "{\"line\":2}", "reject_line_2");
            case "vision_ready" -> postJson("/vision-ready", "{\"value\":true}", "vision_ready pulse→level true");
            case "vision_fault" -> postJson("/vision-fault", "{\"value\":true}", "vision_fault pulse→level true");
            default -> throw new IllegalArgumentException("not a discrete plc signal: " + signalName);
        }
    }

    public void writeSignalLevel(String signalName, boolean value) {
        if (!isEnabled() || signalName == null) {
            return;
        }
        String name = signalName.trim().toLowerCase();
        switch (name) {
            case "vision_ready" -> postJson("/vision-ready", "{\"value\":" + value + "}", "vision_ready=" + value);
            case "vision_fault" -> postJson("/vision-fault", "{\"value\":" + value + "}", "vision_fault=" + value);
            case "reject_line_1" -> {
                if (value) {
                    postJson("/reject", "{\"line\":1}", "reject_line_1");
                }
            }
            case "reject_line_2" -> {
                if (value) {
                    postJson("/reject", "{\"line\":2}", "reject_line_2");
                }
            }
            default -> throw new IllegalArgumentException("not a discrete plc signal: " + signalName);
        }
    }

    public static boolean isDiscreteSignal(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase();
        return "reject_line_1".equals(n)
                || "reject_line_2".equals(n)
                || "vision_ready".equals(n)
                || "vision_fault".equals(n);
    }

    public static boolean isRejectSignal(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase();
        return "reject_line_1".equals(n) || "reject_line_2".equals(n);
    }

    private void postJson(String path, String body, String label) {
        String url = baseUrl + path;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(3000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("io_input_monitor discrete ok {} url={} body={}", label, url, response.body());
            } else {
                log.warn(
                        "io_input_monitor discrete failed status={} {} url={} body={}",
                        response.statusCode(),
                        label,
                        url,
                        response.body()
                );
            }
        } catch (Exception e) {
            log.warn("io_input_monitor discrete error {} url={}: {}", label, url, e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
