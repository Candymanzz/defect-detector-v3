package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficListener;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Техзрение → IoInputMonitor DO → физические входы ПЛК (X4 ready, X5 fault, X6/X7 брак).
 * События уходят в UI как {@link PlcFinsTrafficEvent} (operation=discrete_di).
 */
public final class IoInputMonitorRejectClient implements AutoCloseable {

    public static final String OP_DISCRETE_DI = "discrete_di";

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
    private final AtomicReference<PlcFinsTrafficListener> trafficListener = new AtomicReference<>();
    private final ConcurrentHashMap<String, Boolean> lastSignalValues = new ConcurrentHashMap<>();

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

    public void setTrafficListener(PlcFinsTrafficListener listener) {
        trafficListener.set(listener);
    }

    public boolean isEnabled() {
        return enabled && !baseUrl.isEmpty();
    }

    public Boolean lastSignalValue(String name) {
        return name == null ? null : lastSignalValues.get(name.trim().toLowerCase());
    }

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
        String signal = line == 1 ? "reject_line_1" : "reject_line_2";
        executor.execute(() -> postJson(
                "/reject",
                "{\"line\":" + line + "}",
                signal,
                true,
                "reject line=" + line + " seq=" + result.triggerSequence()
        ));
    }

    public void setVisionReady(boolean ready) {
        if (!isEnabled()) {
            return;
        }
        executor.execute(() -> postJson(
                "/vision-ready",
                "{\"value\":" + ready + "}",
                "vision_ready",
                ready,
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
                "vision_fault",
                fault,
                "vision_fault=" + fault
        ));
    }

    public void pulseSignal(String signalName) {
        if (!isEnabled() || signalName == null) {
            return;
        }
        String name = signalName.trim().toLowerCase();
        switch (name) {
            case "reject_line_1" -> postJson("/reject", "{\"line\":1}", name, true, name);
            case "reject_line_2" -> postJson("/reject", "{\"line\":2}", name, true, name);
            case "vision_ready" -> postJson("/vision-ready", "{\"value\":true}", name, true, name);
            case "vision_fault" -> postJson("/vision-fault", "{\"value\":true}", name, true, name);
            default -> throw new IllegalArgumentException("not a discrete plc signal: " + signalName);
        }
    }

    public void writeSignalLevel(String signalName, boolean value) {
        if (!isEnabled() || signalName == null) {
            return;
        }
        String name = signalName.trim().toLowerCase();
        switch (name) {
            case "vision_ready" -> postJson("/vision-ready", "{\"value\":" + value + "}", name, value, name + "=" + value);
            case "vision_fault" -> postJson("/vision-fault", "{\"value\":" + value + "}", name, value, name + "=" + value);
            case "reject_line_1" -> {
                if (value) {
                    postJson("/reject", "{\"line\":1}", name, true, name);
                }
            }
            case "reject_line_2" -> {
                if (value) {
                    postJson("/reject", "{\"line\":2}", name, true, name);
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

    private void postJson(String path, String body, String signal, boolean logicalValue, String label) {
        String url = baseUrl + path;
        String fallbackAddress = fallbackAddress(signal);
        long ts = System.currentTimeMillis();
        emitTraffic(
                PlcFinsTrafficEvent.DIRECTION_REQUEST,
                signal,
                fallbackAddress,
                logicalValue,
                "",
                true,
                null,
                ts
        );
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(3000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String address = parseAddress(response.body(), fallbackAddress);
            if (ok) {
                lastSignalValues.put(signal, logicalValue);
                log.info(
                        "plc discrete DI ok signal={} address={} value={} label={} body={}",
                        signal,
                        address,
                        logicalValue,
                        label,
                        response.body()
                );
                emitTraffic(
                        PlcFinsTrafficEvent.DIRECTION_RESPONSE,
                        signal,
                        address,
                        logicalValue,
                        response.body(),
                        true,
                        null,
                        System.currentTimeMillis()
                );
            } else {
                log.warn(
                        "plc discrete DI failed status={} signal={} address={} label={} body={}",
                        response.statusCode(),
                        signal,
                        address,
                        label,
                        response.body()
                );
                emitTraffic(
                        PlcFinsTrafficEvent.DIRECTION_RESPONSE,
                        signal,
                        address,
                        logicalValue,
                        response.body(),
                        false,
                        "HTTP " + response.statusCode(),
                        System.currentTimeMillis()
                );
            }
        } catch (Exception e) {
            log.warn(
                    "plc discrete DI error signal={} address={} label={} url={}: {}",
                    signal,
                    fallbackAddress,
                    label,
                    url,
                    e.getMessage()
            );
            emitTraffic(
                    PlcFinsTrafficEvent.DIRECTION_RESPONSE,
                    signal,
                    fallbackAddress,
                    logicalValue,
                    "",
                    false,
                    e.getMessage(),
                    System.currentTimeMillis()
            );
        }
    }

    private void emitTraffic(
            String direction,
            String signal,
            String address,
            Object value,
            String detail,
            boolean ok,
            String error,
            long tsMs
    ) {
        PlcFinsTrafficListener listener = trafficListener.get();
        if (listener == null) {
            return;
        }
        try {
            listener.onTraffic(new PlcFinsTrafficEvent(
                    direction,
                    OP_DISCRETE_DI,
                    signal,
                    "DO→DI",
                    address,
                    value,
                    detail == null ? "" : detail,
                    null,
                    ok ? "0000" : "FAIL",
                    ok,
                    error,
                    tsMs
            ));
        } catch (RuntimeException e) {
            log.debug("plc discrete traffic listener failed: {}", e.getMessage());
        }
    }

    private static String fallbackAddress(String signal) {
        return switch (signal == null ? "" : signal) {
            case "vision_ready" -> "DO1→X4";
            case "vision_fault" -> "DO2→X5";
            case "reject_line_1" -> "DO3→X6";
            case "reject_line_2" -> "DO4→X7";
            default -> signal == null ? "?" : signal;
        };
    }

    private static String parseAddress(String responseBody, String fallback) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallback;
        }
        try {
            // лёгкий разбор без полной JSON-библиотеки: "do_port":3 ... "plc_input":"X6"
            Integer doPort = extractInt(responseBody, "do_port");
            String plcInput = extractString(responseBody, "plc_input");
            if (doPort != null && plcInput != null && !plcInput.isBlank()) {
                return "DO" + doPort + "→" + plcInput;
            }
            if (doPort != null) {
                return "DO" + doPort;
            }
            if (plcInput != null && !plcInput.isBlank()) {
                return plcInput;
            }
        } catch (RuntimeException ignored) {
            // fallback
        }
        return fallback;
    }

    private static Integer extractInt(String json, String key) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            i++;
        }
        if (start == i) {
            return null;
        }
        return Integer.parseInt(json.substring(start, i));
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
