package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.config.YamlScalars;
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
 * Техзрение → IoInputMonitor DO → физические входы ПЛК.
 * Порты DO, метки PLC и enable-флаги — только из {@code integration.io_input_monitor_reject}.
 */
public final class IoInputMonitorRejectClient implements AutoCloseable {

    public static final String OP_DISCRETE_DI = "discrete_di";

    private final Logger log;
    private final boolean enabled;
    private final String baseUrl;
    private final boolean readyEnabled;
    private final boolean faultEnabled;
    private final boolean line1Enabled;
    private final boolean line2Enabled;
    private final int readyOutputPort;
    private final int faultOutputPort;
    private final int line1OutputPort;
    private final int line2OutputPort;
    private final String readyPlcInput;
    private final String faultPlcInput;
    private final String line1PlcInput;
    private final String line2PlcInput;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "io-plc-discrete");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<PlcFinsTrafficListener> trafficListener = new AtomicReference<>();
    private final ConcurrentHashMap<String, Boolean> lastSignalValues = new ConcurrentHashMap<>();

    public IoInputMonitorRejectClient(Logger log, boolean enabled, String baseUrl) {
        this(log, Defaults.from(enabled, baseUrl));
    }

    private IoInputMonitorRejectClient(Logger log, Defaults d) {
        this.log = log;
        this.enabled = d.enabled;
        this.baseUrl = d.baseUrl;
        this.readyEnabled = d.readyEnabled;
        this.faultEnabled = d.faultEnabled;
        this.line1Enabled = d.line1Enabled;
        this.line2Enabled = d.line2Enabled;
        this.readyOutputPort = d.readyOutputPort;
        this.faultOutputPort = d.faultOutputPort;
        this.line1OutputPort = d.line1OutputPort;
        this.line2OutputPort = d.line2OutputPort;
        this.readyPlcInput = d.readyPlcInput;
        this.faultPlcInput = d.faultPlcInput;
        this.line1PlcInput = d.line1PlcInput;
        this.line2PlcInput = d.line2PlcInput;
        this.connectTimeout = Duration.ofMillis(d.connectTimeoutMs);
        this.requestTimeout = Duration.ofMillis(d.requestTimeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.connectTimeout).build();
    }

    @SuppressWarnings("unchecked")
    public static IoInputMonitorRejectClient fromIntegration(Logger log, Map<String, Object> integration) {
        Defaults d = Defaults.from(true, "http://127.0.0.1:9101");
        if (integration != null) {
            Object raw = integration.get("io_input_monitor_reject");
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> section = (Map<String, Object>) map;
                d = Defaults.fromSection(section, d);
            }
            Object legacyUrl = integration.get("io_input_monitor_direction_url");
            if ((d.baseUrl == null || d.baseUrl.isBlank()) && legacyUrl != null) {
                d = d.withBaseUrl(String.valueOf(legacyUrl).trim());
            }
        }
        return new IoInputMonitorRejectClient(log, d);
    }

    public void setTrafficListener(PlcFinsTrafficListener listener) {
        trafficListener.set(listener);
    }

    public boolean isEnabled() {
        return enabled && !baseUrl.isEmpty();
    }

    public boolean readyEnabled() {
        return readyEnabled;
    }

    public boolean faultEnabled() {
        return faultEnabled;
    }

    public boolean line1Enabled() {
        return line1Enabled;
    }

    public boolean line2Enabled() {
        return line2Enabled;
    }

    public Boolean lastSignalValue(String name) {
        return name == null ? null : lastSignalValues.get(name.trim().toLowerCase());
    }

    public void publishBucket(BucketFanOutResult result) {
        if (!isEnabled() || result == null) {
            return;
        }
        int groupId = result.groupId();
        if (groupId != 0 && groupId != 1) {
            log.warn("io_input_monitor reject skip: unsupported group_id={}", groupId);
            return;
        }
        long seq = result.triggerSequence();
        if (result.overallPass()) {
            return;
        }
        int line = groupId + 1;
        String rejectSignal = line == 1 ? "reject_line_1" : "reject_line_2";
        if (line == 1 && !line1Enabled) {
            log.debug("reject line=1 skipped — line1_enabled=false seq={}", seq);
            return;
        }
        if (line == 2 && !line2Enabled) {
            log.debug("reject line=2 skipped — line2_enabled=false seq={}", seq);
            return;
        }
        executor.execute(() -> postJson(
                "/reject",
                "{\"line\":" + line + "}",
                rejectSignal,
                true,
                "reject line=" + line + " seq=" + seq
        ));
    }

    public void setVisionReady(boolean ready) {
        // DO1/vision_ready не используем — на ПЛК только DO3/DO4 при браке.
    }

    public void setVisionFault(boolean fault) {
        // DO2/vision_fault не используем — на ПЛК только DO3/DO4 при браке.
    }

    public void pulseSignal(String signalName) {
        if (!isEnabled() || signalName == null) {
            return;
        }
        String name = signalName.trim().toLowerCase();
        switch (name) {
            case "reject_line_1" -> {
                if (line1Enabled) {
                    postJson("/reject", "{\"line\":1}", name, true, name);
                }
            }
            case "reject_line_2" -> {
                if (line2Enabled) {
                    postJson("/reject", "{\"line\":2}", name, true, name);
                }
            }
            case "vision_ready", "vision_fault" -> {
                // ignore — на ПЛК только DO3/DO4
            }
            default -> throw new IllegalArgumentException("not a discrete plc signal: " + signalName);
        }
    }

    public void writeSignalLevel(String signalName, boolean value) {
        if (!isEnabled() || signalName == null) {
            return;
        }
        String name = signalName.trim().toLowerCase();
        switch (name) {
            case "vision_ready", "vision_fault" -> {
                // ignore — на ПЛК только DO3/DO4
            }
            case "reject_line_1" -> {
                if (value && line1Enabled) {
                    postJson("/reject", "{\"line\":1}", name, true, name);
                }
            }
            case "reject_line_2" -> {
                if (value && line2Enabled) {
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
                    .timeout(requestTimeout)
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

    private String fallbackAddress(String signal) {
        return switch (signal == null ? "" : signal) {
            case "vision_ready" -> "DO" + readyOutputPort + "→" + readyPlcInput;
            case "vision_fault" -> "DO" + faultOutputPort + "→" + faultPlcInput;
            case "reject_line_1" -> "DO" + line1OutputPort + "→" + line1PlcInput;
            case "reject_line_2" -> "DO" + line2OutputPort + "→" + line2PlcInput;
            default -> signal == null ? "?" : signal;
        };
    }

    private static String parseAddress(String responseBody, String fallback) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallback;
        }
        try {
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

    private record Defaults(
            boolean enabled,
            String baseUrl,
            boolean readyEnabled,
            boolean faultEnabled,
            boolean line1Enabled,
            boolean line2Enabled,
            int readyOutputPort,
            int faultOutputPort,
            int line1OutputPort,
            int line2OutputPort,
            String readyPlcInput,
            String faultPlcInput,
            String line1PlcInput,
            String line2PlcInput,
            int connectTimeoutMs,
            int requestTimeoutMs
    ) {
        static Defaults from(boolean enabled, String baseUrl) {
            return new Defaults(
                    enabled,
                    baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""),
                    false,
                    false,
                    true,
                    true,
                    1,
                    2,
                    3,
                    4,
                    "X4",
                    "X5",
                    "X6",
                    "X7",
                    500,
                    3000
            );
        }

        static Defaults fromSection(Map<String, Object> section, Defaults fallback) {
            String url = fallback.baseUrl;
            Object urlRaw = section.get("url");
            if (urlRaw != null) {
                String configured = String.valueOf(urlRaw).trim();
                if (!configured.isEmpty()) {
                    url = configured.replaceAll("/+$", "");
                }
            }
            return new Defaults(
                    YamlScalars.toBool(section.get("enabled"), fallback.enabled),
                    url,
                    YamlScalars.toBool(section.get("ready_enabled"), fallback.readyEnabled),
                    YamlScalars.toBool(section.get("fault_enabled"), fallback.faultEnabled),
                    YamlScalars.toBool(section.get("line1_enabled"), fallback.line1Enabled),
                    YamlScalars.toBool(section.get("line2_enabled"), fallback.line2Enabled),
                    clampDo(YamlScalars.toInt(section.get("ready_output_port"), fallback.readyOutputPort)),
                    clampDo(YamlScalars.toInt(section.get("fault_output_port"), fallback.faultOutputPort)),
                    clampDo(YamlScalars.toInt(section.get("line1_output_port"), fallback.line1OutputPort)),
                    clampDo(YamlScalars.toInt(section.get("line2_output_port"), fallback.line2OutputPort)),
                    nonEmpty(section.get("ready_plc_input"), fallback.readyPlcInput),
                    nonEmpty(section.get("fault_plc_input"), fallback.faultPlcInput),
                    nonEmpty(section.get("line1_plc_input"), fallback.line1PlcInput),
                    nonEmpty(section.get("line2_plc_input"), fallback.line2PlcInput),
                    Math.max(100, YamlScalars.toInt(section.get("connect_timeout_ms"), fallback.connectTimeoutMs)),
                    Math.max(200, YamlScalars.toInt(section.get("request_timeout_ms"), fallback.requestTimeoutMs))
            );
        }

        Defaults withBaseUrl(String url) {
            return new Defaults(
                    enabled,
                    url == null ? "" : url.trim().replaceAll("/+$", ""),
                    readyEnabled,
                    faultEnabled,
                    line1Enabled,
                    line2Enabled,
                    readyOutputPort,
                    faultOutputPort,
                    line1OutputPort,
                    line2OutputPort,
                    readyPlcInput,
                    faultPlcInput,
                    line1PlcInput,
                    line2PlcInput,
                    connectTimeoutMs,
                    requestTimeoutMs
            );
        }

        private static int clampDo(int port) {
            return port >= 1 && port <= 8 ? port : 1;
        }

        private static String nonEmpty(Object raw, String fallback) {
            if (raw == null) {
                return fallback;
            }
            String s = String.valueOf(raw).trim();
            return s.isEmpty() ? fallback : s;
        }
    }
}
