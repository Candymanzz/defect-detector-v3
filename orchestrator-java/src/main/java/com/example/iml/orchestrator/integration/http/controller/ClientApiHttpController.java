package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
import com.example.iml.orchestrator.integration.clientapi.LearnedReviewIndex;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientApiHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClientApiMount clientApi;

    public ClientApiHttpController(ClientApiMount clientApi) {
        this.clientApi = clientApi;
    }

    public void handleClientApi(HttpRequestContext ctx) throws IOException {
        String method = ctx.method();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpResponses.corsPreflight(ctx.exchange(), "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            return;
        }
        if (!clientApi.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "client_api disabled");
            return;
        }
        String path = ctx.path();
        if (path.startsWith("/api/client/geometry-runtime")) {
            handleGeometryRuntime(ctx);
            return;
        }
        if (path.equals("/api/client/line-direction")) {
            handleLineDirection(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/status")) {
            handleInspectionStatus(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/clear-reference")) {
            handleClearReference(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/stop-all")) {
            handleInspectionStopAll(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/start-all")) {
            handleInspectionStartAll(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/stop")) {
            handleInspectionToggle(ctx, false);
            return;
        }
        if (path.equals("/api/client/inspection/start")) {
            handleInspectionToggle(ctx, true);
            return;
        }
        if (path.equals("/api/client/inspection/test-analyze")) {
            handleTestAnalyze(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/test-pin")) {
            handleTestPin(ctx);
            return;
        }
        if (path.startsWith("/api/client/inspection/test-pin/cameras/") && path.endsWith("/frame.jpg")) {
            handleTestPinFrameGet(ctx, path);
            return;
        }
        if (path.equals("/api/client/learning/accept-all-as-normal") || path.startsWith("/api/client/learning/")) {
            handleLearning(ctx);
            return;
        }
        if (path.equals("/api/client/mode") || path.equals("/api/client/mode/test")) {
            handleProgramMode(ctx, path);
            return;
        }
        if (path.equals("/api/client/plc/timeouts")
                || path.equals("/api/client/plc/status")
                || path.equals("/api/client/plc/signals")) {
            handlePlc(ctx, path);
            return;
        }
        if (!clientApi.kopcheniConfigured()) {
            HttpResponses.sendJsonError(ctx, 503, "client_api.kopcheni_base_url not set");
            return;
        }
        KopcheniHttpProxy.forward(ctx.exchange(), clientApi.kopcheniBaseUrl(), path);
    }

    private void handlePlc(HttpRequestContext ctx, String path) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        var plc = clientApi.plcFinsHolder() == null ? null : clientApi.plcFinsHolder().get();
        if (plc == null) {
            HttpResponses.sendJsonError(ctx, 503, "plc fins not ready");
            return;
        }
        if (path.equals("/api/client/plc/status")) {
            if (!"GET".equalsIgnoreCase(ctx.method())) {
                HttpResponses.methodNotAllowed(ctx);
                return;
            }
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("enabled", plc.enabled());
            root.put("inspection_in_flight", plc.inspectionInFlight());
            root.put("inspection_enabled", plc.inspectionEnabled());
            root.put("editable", plc.manualControlEditable());
            root.put("timeouts_editable", plc.timeoutsEditable());
            root.put("signals_editable", plc.manualControlEditable());
            root.set("timeout_definitions", JSON.valueToTree(plc.timeoutDefinitions()));
            root.set("signals", JSON.valueToTree(plc.listSignals()));
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if (path.equals("/api/client/plc/signals")) {
            handlePlcSignals(ctx, plc);
            return;
        }
        if (!"GET".equalsIgnoreCase(ctx.method()) && !"PUT".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (!plc.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "plc_fins disabled");
            return;
        }
        try {
            if ("GET".equalsIgnoreCase(ctx.method())) {
                sendTimeoutsResponse(ctx, plc, plc.readTimeouts());
                return;
            }
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body.timeouts required");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            Map<String, Integer> units = parseTimeoutUnits(body);
            if (units.isEmpty()) {
                HttpResponses.sendJsonError(ctx, 400, "body.timeouts required (D4400..D4405 or names)");
                return;
            }
            sendTimeoutsResponse(ctx, plc, plc.writeTimeouts(units));
        } catch (IllegalStateException e) {
            HttpResponses.sendJsonError(ctx, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJsonError(ctx, 400, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpResponses.sendJsonError(ctx, 503, "plc fins interrupted");
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handlePlcSignals(
            HttpRequestContext ctx,
            com.example.iml.orchestrator.integration.plc.PlcFinsApi plc
    ) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method()) && !"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (!plc.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "plc_fins disabled");
            return;
        }
        try {
            if ("GET".equalsIgnoreCase(ctx.method())) {
                sendSignalsResponse(ctx, plc, plc.listSignals());
                return;
            }
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body required");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            Map<String, Boolean> values = new java.util.LinkedHashMap<>();
            Map<String, Boolean> pulses = new java.util.LinkedHashMap<>();
            Object signalName = body.get("signal");
            if (signalName != null) {
                String name = String.valueOf(signalName).trim();
                values.put(name, toBool(body.get("value"), true));
                pulses.put(name, toBool(body.get("pulse"), false));
            }
            Object signalsRaw = body.get("signals");
            if (signalsRaw instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String name = String.valueOf(entry.getKey()).trim();
                    Object value = entry.getValue();
                    if (value instanceof Map<?, ?> nested) {
                        Object nestedValue = nested.get("value");
                        values.put(name, toBool(nestedValue, true));
                        pulses.put(name, toBool(nested.get("pulse"), false));
                    } else {
                        values.put(name, toBool(value, true));
                    }
                }
            }
            Object pulseMapRaw = body.get("pulse");
            if (pulseMapRaw instanceof Map<?, ?> pulseMap && signalName == null) {
                for (Map.Entry<?, ?> entry : pulseMap.entrySet()) {
                    pulses.put(String.valueOf(entry.getKey()).trim(), toBool(entry.getValue(), false));
                }
            }
            if (values.isEmpty()) {
                HttpResponses.sendJsonError(ctx, 400, "body.signal or body.signals required");
                return;
            }
            sendSignalsResponse(ctx, plc, plc.writeSignals(values, pulses));
        } catch (IllegalStateException e) {
            HttpResponses.sendJsonError(ctx, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJsonError(ctx, 400, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpResponses.sendJsonError(ctx, 503, "plc fins interrupted");
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean toBool(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text);
    }

    private void sendSignalsResponse(
            HttpRequestContext ctx,
            com.example.iml.orchestrator.integration.plc.PlcFinsApi plc,
            java.util.List<com.example.iml.orchestrator.integration.plc.PlcSignalState> signals
    ) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("enabled", plc.enabled());
        root.put("inspection_in_flight", plc.inspectionInFlight());
        root.put("inspection_enabled", plc.inspectionEnabled());
        root.put("editable", plc.manualControlEditable());
        root.put("timeouts_editable", plc.timeoutsEditable());
        root.put("signals_editable", plc.manualControlEditable());
        root.set("signals", JSON.valueToTree(signals));
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    private void sendTimeoutsResponse(
            HttpRequestContext ctx,
            com.example.iml.orchestrator.integration.plc.PlcFinsApi plc,
            java.util.List<com.example.iml.orchestrator.integration.plc.PlcTimeoutState> timeouts
    ) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("enabled", plc.enabled());
        root.put("inspection_in_flight", plc.inspectionInFlight());
        root.put("inspection_enabled", plc.inspectionEnabled());
        root.put("editable", plc.manualControlEditable());
        root.put("timeouts_editable", plc.timeoutsEditable());
        root.put("signals_editable", plc.manualControlEditable());
        root.put("unit", "100ms_bcd");
        root.set("timeouts", JSON.valueToTree(timeouts));
        root.set("signals", JSON.valueToTree(plc.listSignals()));
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseTimeoutUnits(Map<String, Object> body) {
        Object raw = body.get("timeouts");
        if (raw == null) {
            raw = body;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> units = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("ok".equals(key) || "enabled".equals(key) || "editable".equals(key)
                    || "inspection_in_flight".equals(key) || "unit".equals(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                Object unitsRaw = nested.get("value_units");
                if (unitsRaw == null) {
                    unitsRaw = nested.get("valueUnits");
                }
                if (unitsRaw == null) {
                    unitsRaw = nested.get("units");
                }
                if (unitsRaw == null) {
                    unitsRaw = nested.get("value_ms");
                    if (unitsRaw instanceof Number ms) {
                        units.put(key, ms.intValue() / 100);
                        continue;
                    }
                }
                value = unitsRaw;
            }
            if (value instanceof Number number) {
                units.put(key, number.intValue());
            } else if (value != null && !String.valueOf(value).isBlank()) {
                units.put(key, Integer.parseInt(String.valueOf(value).trim()));
            }
        }
        return units;
    }

    private void handleGeometryRuntime(HttpRequestContext ctx) throws IOException {
        String m = ctx.method();
        if (clientApi.geometryRuntime() == null) {
            HttpResponses.sendJsonError(ctx, 503, "geometry runtime not configured");
            return;
        }
        HttpResponses.corsJson(ctx.exchange());
        String analysisProfile = resolveAnalysisProfile(ctx);
        if ("GET".equalsIgnoreCase(m)) {
            ObjectNode root = JSON.createObjectNode();
            root.set("runtimeOverrides", JSON.valueToTree(clientApi.geometryRuntime().overridesCopy(analysisProfile)));
            root.set(
                    "effectiveForNextGeometryInspect",
                    JSON.valueToTree(clientApi.geometryRuntime().effectiveForDisplay(
                            clientApi.javaGeometryYaml(),
                            clientApi.pythonDetectorYaml(),
                            analysisProfile
                    ))
            );
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if ("PUT".equalsIgnoreCase(m)) {
            byte[] raw = ctx.readBody();
            if (raw.length > 0) {
                Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
                });
                clientApi.geometryRuntime().replaceAllFromClient(analysisProfile, body);
            }
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("PATCH".equalsIgnoreCase(m)) {
            byte[] raw = ctx.readBody();
            if (raw.length > 0) {
                Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
                });
                clientApi.geometryRuntime().mergeFromClient(analysisProfile, body);
            }
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("DELETE".equalsIgnoreCase(m)) {
            clientApi.geometryRuntime().clear(analysisProfile);
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void handleLineDirection(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        ManualLineDirectionService lineDirection = clientApi.manualLineDirection();
        if (lineDirection == null) {
            HttpResponses.sendJsonError(ctx, 503, "line direction not configured");
            return;
        }
        String m = ctx.method();
        if ("GET".equalsIgnoreCase(m)) {
            ObjectNode root = JSON.createObjectNode();
            root.put("direction", lineDirection.wireValue());
            root.put("source", "manual");
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if ("PUT".equalsIgnoreCase(m)) {
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body.direction required (forward|reverse)");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            Object directionRaw = body.get("direction");
            if (directionRaw == null) {
                HttpResponses.sendJsonError(ctx, 400, "body.direction required (forward|reverse)");
                return;
            }
            try {
                lineDirection.setFromWireValue(String.valueOf(directionRaw));
            } catch (IllegalArgumentException e) {
                HttpResponses.sendJsonError(ctx, 400, e.getMessage());
                return;
            }
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("direction", lineDirection.wireValue());
            root.put("source", "manual");
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void handleProgramMode(HttpRequestContext ctx, String path) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        var holder = clientApi.clientWsHolder();
        var ws = holder == null ? null : holder.get();
        if (ws == null) {
            HttpResponses.sendJsonError(ctx, 503, "client_ws not ready");
            return;
        }
        if ("GET".equalsIgnoreCase(ctx.method()) && path.equals("/api/client/mode")) {
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("session_state", ws.sessionState().name());
            root.put("test_mode", ws.isTestMode());
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (!path.equals("/api/client/mode/test")) {
            HttpResponses.sendJsonError(ctx, 404, "unknown mode endpoint");
            return;
        }
        Map<String, Object> body;
        try {
            byte[] raw = ctx.readBody();
            body = raw.length == 0 ? Map.of() : JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid json body");
            return;
        }
        Object enabledRaw = body.get("enabled");
        if (enabledRaw == null) {
            enabledRaw = body.get("test_mode");
        }
        if (enabledRaw == null) {
            HttpResponses.sendJsonError(ctx, 400, "enabled required");
            return;
        }
        boolean enabled = enabledRaw instanceof Boolean b
                ? b
                : Boolean.parseBoolean(String.valueOf(enabledRaw));
        if (enabled) {
            if (ws.sessionState() == ClientWsSessionState.NO_REFERENCE) {
                HttpResponses.sendJsonError(ctx, 409, "reference required to enter TEST mode");
                return;
            }
            if (!ws.enterTestMode()) {
                HttpResponses.sendJsonError(ctx, 409, "cannot enter TEST mode");
                return;
            }
            // Stop production DI3 cycles; operator uses test-analyze in this mode.
            if (clientApi.inspectionGate() != null) {
                clientApi.inspectionGate().disableAllAndRequestCancel();
            }
        } else {
            if (ws.isTestMode()) {
                ws.exitTestMode();
            }
            var testAnalyzeHolder = clientApi.uiTestAnalyzeHolder();
            var testAnalyze = testAnalyzeHolder == null ? null : testAnalyzeHolder.get();
            if (testAnalyze != null) {
                testAnalyze.clearPins();
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("session_state", ws.sessionState().name());
        root.put("test_mode", ws.isTestMode());
        root.put(
                "message",
                ws.isTestMode()
                        ? "Режим теста: прод-триггер остановлен, используйте «Проверить на кадре»"
                        : "Прод-режим: Пуск инспекции для ожидания DI3"
        );
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    private void handleLearning(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        String pythonBase = pythonBaseUrl();
        if (pythonBase.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 503, "python detector base url not configured");
            return;
        }
        String path = ctx.path();
        if (path.equals("/api/client/learning/accept-all-as-normal")) {
            handleAcceptAllAsNormal(ctx, pythonBase);
            return;
        }
        String pythonPath = path.substring("/api/client".length());
        String query = ctx.query();
        if (query != null && !query.isBlank()) {
            pythonPath = pythonPath + "?" + rewriteLearningQuery(query);
        }
        KopcheniHttpProxy.forwardTarget(ctx.exchange(), pythonBase, pythonPath, null);
    }

    private void handleAcceptAllAsNormal(HttpRequestContext ctx, String pythonBase) throws IOException {
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        Map<String, Object> body;
        try {
            byte[] raw = ctx.readBody();
            body = raw.length == 0 ? Map.of() : JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid json body");
            return;
        }
        Long frameId = parseLongId(first(body, "frameId", "frame_id"));
        String productType = stringId(first(body, "productType", "product_type"));
        Integer cameraId = parseIntId(first(body, "cameraId", "camera_id"));
        String learnedReviewId = stringId(first(body, "learnedReviewId", "learned_review_id"));
        if (learnedReviewId == null && (frameId == null || productType == null)) {
            HttpResponses.sendJsonError(ctx, 400, "body.frameId and body.productType required");
            return;
        }
        if (learnedReviewId == null) {
            String scoped = LearnedReviewIndex.scopedProductType(productType, cameraId);
            learnedReviewId = LearnedReviewIndex.lookup(cameraId, frameId, scoped);
            if (learnedReviewId == null) {
                learnedReviewId = LearnedReviewIndex.lookup(cameraId, frameId, productType);
            }
        }
        if (learnedReviewId == null) {
            HttpResponses.sendJsonError(ctx, 404, "learned review not found for frameId/productType");
            return;
        }
        ObjectNode pythonBody = JSON.createObjectNode();
        Object note = first(body, "note");
        pythonBody.put("note", note == null ? "" : String.valueOf(note));
        KopcheniHttpProxy.forwardTarget(
                ctx.exchange(),
                pythonBase,
                "/learning/reviews/" + urlEncode(learnedReviewId) + "/accept-all-as-normal",
                JSON.writeValueAsBytes(pythonBody)
        );
    }

    private String rewriteLearningQuery(String query) {
        Integer cameraId = null;
        String productType = null;
        StringBuilder kept = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] kv = part.split("=", 2);
            String key = urlDecode(kv[0]);
            String value = kv.length > 1 ? urlDecode(kv[1]) : "";
            if ("cameraId".equals(key) || "camera_id".equals(key)) {
                cameraId = parseIntId(value);
                continue;
            }
            if ("productType".equals(key) || "product_type".equals(key)) {
                productType = value;
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(part);
        }
        if (productType != null && !productType.isBlank()) {
            String scoped = LearnedReviewIndex.scopedProductType(productType, cameraId);
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append("product_type=").append(urlEncode(scoped));
        }
        return kept.toString();
    }

    private String pythonBaseUrl() {
        if (clientApi.kopcheniConfigured()) {
            return clientApi.kopcheniBaseUrl();
        }
        Map<String, Object> pythonCfg = clientApi.pythonDetectorYaml();
        if (pythonCfg == null) {
            return "";
        }
        Object url = pythonCfg.get("base_url");
        if (url == null) {
            return "";
        }
        String base = String.valueOf(url).trim();
        if (base.endsWith("/")) {
            return base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static Object first(Map<String, Object> body, String... keys) {
        if (body == null) {
            return null;
        }
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringId(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static Long parseLongId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseIntId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void handleTestAnalyze(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        var holder = clientApi.uiTestAnalyzeHolder();
        var service = holder == null ? null : holder.get();
        if (service == null) {
            HttpResponses.sendJsonError(ctx, 503, "test-analyze not ready");
            return;
        }
        var wsHolder = clientApi.clientWsHolder();
        var ws = wsHolder == null ? null : wsHolder.get();
        if (ws != null && !ws.isTestMode()) {
            HttpResponses.sendJsonError(ctx, 409, "enter TEST mode first (POST /api/client/mode/test)");
            return;
        }
        try {
            var request = parseTestAnalyzeRequest(ctx);
            var accepted = service.submit(request);
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("jobId", accepted.jobId());
            root.put("cameraId", accepted.cameraId());
            root.put("frameId", accepted.frameId());
            HttpResponses.send(ctx, 202, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
        } catch (com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.AnalyzeException e) {
            HttpResponses.sendJsonError(ctx, e.status(), e.getMessage());
        } catch (ClassCastException | NumberFormatException e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid cameraId/frameId: " + e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid json body");
        }
    }

    private void handleTestPin(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        var holder = clientApi.uiTestAnalyzeHolder();
        var service = holder == null ? null : holder.get();
        if (service == null) {
            HttpResponses.sendJsonError(ctx, 503, "test-analyze not ready");
            return;
        }
        var wsHolder = clientApi.clientWsHolder();
        var ws = wsHolder == null ? null : wsHolder.get();
        if (ws != null && !ws.isTestMode()) {
            HttpResponses.sendJsonError(ctx, 409, "enter TEST mode first (POST /api/client/mode/test)");
            return;
        }
        try {
            var request = parseTestAnalyzeRequest(ctx);
            var pinned = service.pin(request);
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("cameraId", pinned.cameraId());
            root.put("frameId", pinned.frameId());
            root.put("pinId", pinned.pinId());
            if (pinned.httpPath() != null) {
                root.put("httpPath", pinned.httpPath());
            }
            if (pinned.sha256() != null && !pinned.sha256().isBlank()) {
                root.put("sha256", pinned.sha256());
            }
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
        } catch (com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.AnalyzeException e) {
            HttpResponses.sendJsonError(ctx, e.status(), e.getMessage());
        } catch (ClassCastException | NumberFormatException e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid cameraId/frameId: " + e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid json body");
        }
    }

    private void handleTestPinFrameGet(HttpRequestContext ctx, String path) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"GET".equalsIgnoreCase(ctx.method()) && !"HEAD".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        var holder = clientApi.uiTestAnalyzeHolder();
        var service = holder == null ? null : holder.get();
        if (service == null) {
            HttpResponses.sendJsonError(ctx, 503, "test-analyze not ready");
            return;
        }
        // /api/client/inspection/test-pin/cameras/{id}/frame.jpg
        String[] parts = path.split("/");
        if (parts.length < 8) {
            HttpResponses.sendJsonError(ctx, 404, "not found");
            return;
        }
        int cameraId;
        try {
            cameraId = Integer.parseInt(parts[6]);
        } catch (NumberFormatException e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid cameraId");
            return;
        }
        var jpeg = service.pinnedJpegPath(cameraId);
        if (jpeg.isEmpty() || !java.nio.file.Files.isRegularFile(jpeg.get())) {
            HttpResponses.sendJsonError(ctx, 404, "no pinned test frame for cameraId=" + cameraId);
            return;
        }
        byte[] body = java.nio.file.Files.readAllBytes(jpeg.get());
        var exchange = ctx.exchange();
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        HttpResponses.send(ctx, 200, "image/jpeg", body);
    }

    private com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.Request parseTestAnalyzeRequest(
            HttpRequestContext ctx
    ) throws IOException, com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.AnalyzeException {
        Map<String, Object> body;
        try {
            body = JSON.readValue(ctx.readBody(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.AnalyzeException(
                    400, "invalid json body"
            );
        }
        if (body == null) {
            body = Map.of();
        }
        int cameraId = body.containsKey("cameraId")
                ? ((Number) body.get("cameraId")).intValue()
                : (body.containsKey("camera_id") ? ((Number) body.get("camera_id")).intValue() : -1);
        String sourceRaw = body.containsKey("source") ? String.valueOf(body.get("source")) : null;
        Long frameId = null;
        if (body.get("frameId") instanceof Number n) {
            frameId = n.longValue();
        } else if (body.get("frame_id") instanceof Number n) {
            frameId = n.longValue();
        } else if (body.get("frameId") != null) {
            frameId = Long.parseLong(String.valueOf(body.get("frameId")).trim());
        } else if (body.get("frame_id") != null) {
            frameId = Long.parseLong(String.valueOf(body.get("frame_id")).trim());
        }
        String httpPath = null;
        if (body.get("httpPath") != null) {
            httpPath = String.valueOf(body.get("httpPath")).trim();
        } else if (body.get("http_path") != null) {
            httpPath = String.valueOf(body.get("http_path")).trim();
        }
        var source = com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.parseSource(sourceRaw);
        return new com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService.Request(
                cameraId, source, frameId, httpPath
        );
    }

    private void handleInspectionStatus(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (clientApi.inspectionGate() == null) {
            HttpResponses.sendJsonError(ctx, 503, "inspection gate not configured");
            return;
        }

        sendInspectionState(ctx, List.of(), Set.of(), Set.of());
    }

    private void handleClearReference(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        var holder = clientApi.clientWsHolder();
        var ws = holder == null ? null : holder.get();
        if (ws == null) {
            HttpResponses.sendJsonError(ctx, 503, "client_ws not ready");
            return;
        }
        boolean hadReference = ws.sessionState() != ClientWsSessionState.NO_REFERENCE;
        if (clientApi.inspectionGate() != null) {
            for (Integer cameraId : clientApi.inspectionGate().cameraIds()) {
                clientApi.inspectionGate().requestCancel(cameraId);
            }
        }
        ws.clearReferenceSession();
        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("cleared", hadReference);
        root.put("session_state", ws.sessionState().name());
        var plc = clientApi.plcFinsHolder() == null ? null : clientApi.plcFinsHolder().get();
        if (plc != null) {
            root.put("inspection_enabled", plc.inspectionEnabled());
            root.put("signals_editable", plc.manualControlEditable());
            root.put("timeouts_editable", plc.timeoutsEditable());
        }
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    private void handleInspectionToggle(HttpRequestContext ctx, boolean enabled) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (clientApi.inspectionGate() == null) {
            HttpResponses.sendJsonError(ctx, 503, "inspection gate not configured");
            return;
        }

        var ws = clientApi.clientWsHolder() == null ? null : clientApi.clientWsHolder().get();
        if (enabled && (ws == null || ws.sessionState() == ClientWsSessionState.NO_REFERENCE)) {
            HttpResponses.sendJsonError(ctx, 409, "reference is not set");
            return;
        }
        if (enabled && ws != null && ws.isTestMode()) {
            HttpResponses.sendJsonError(ctx, 409, "exit TEST mode before starting production inspection");
            return;
        }

        byte[] raw = ctx.readBody();
        Map<String, Object> body = raw.length == 0 ? Map.of() : JSON.readValue(raw, new TypeReference<>() {
        });
        List<Integer> requestedCameraIds = parseCameraIds(body);
        if (requestedCameraIds.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 400, "cameraId or cameraIds is required");
            return;
        }

        Set<Integer> changed = new LinkedHashSet<>();
        Set<Integer> cancelled = new LinkedHashSet<>();
        Set<Integer> unknown = new LinkedHashSet<>();
        for (Integer cameraId : requestedCameraIds) {
            if (!clientApi.inspectionGate().isKnownCamera(cameraId)) {
                unknown.add(cameraId);
                continue;
            }
            boolean wasEnabled = clientApi.inspectionGate().isInspectionEnabled(cameraId);
            if (wasEnabled != enabled) {
                changed.add(cameraId);
            }
            if (enabled) {
                clientApi.inspectionGate().setInspectionEnabled(cameraId, true);
                if (clientApi.inspectionResumeHolder() != null) {
                    clientApi.inspectionResumeHolder().resumeCamera(cameraId);
                }
            } else if (clientApi.inspectionGate().disableInspectionAndRequestCancel(cameraId)) {
                cancelled.add(cameraId);
            } else {
                // Already idle/disabled — still mark disabled so bucket no longer waits for this cam.
                clientApi.inspectionGate().setInspectionEnabled(cameraId, false);
            }
        }
        if (!enabled && clientApi.inspectionResumeHolder() != null) {
            clientApi.inspectionResumeHolder().reevaluateOpenBucketsAfterGateChange();
        }

        sendInspectionState(ctx, requestedCameraIds, changed, cancelled, unknown);
    }

    /** Soft-stop всех камер без сброса эталона (как per-camera stop). */
    private void handleInspectionStopAll(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (clientApi.inspectionGate() == null) {
            HttpResponses.sendJsonError(ctx, 503, "inspection gate not configured");
            return;
        }

        List<Integer> requestedCameraIds = new ArrayList<>(clientApi.inspectionGate().cameraIds());
        requestedCameraIds.sort(Integer::compareTo);
        Set<Integer> changed = new LinkedHashSet<>();
        for (Integer cameraId : requestedCameraIds) {
            if (clientApi.inspectionGate().isInspectionEnabled(cameraId)) {
                changed.add(cameraId);
            }
        }
        Set<Integer> cancelled = clientApi.inspectionGate().disableAllAndRequestCancel();
        if (clientApi.inspectionResumeHolder() != null) {
            clientApi.inspectionResumeHolder().reevaluateOpenBucketsAfterGateChange();
        }
        sendInspectionState(ctx, requestedCameraIds, changed, cancelled, Set.of());
    }

    /** Включает все камеры со следующего нового триггера, без rejoin в уже открытую группу. */
    private void handleInspectionStartAll(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (clientApi.inspectionGate() == null) {
            HttpResponses.sendJsonError(ctx, 503, "inspection gate not configured");
            return;
        }
        var ws = clientApi.clientWsHolder() == null ? null : clientApi.clientWsHolder().get();
        if (ws == null || ws.sessionState() == ClientWsSessionState.NO_REFERENCE) {
            HttpResponses.sendJsonError(ctx, 409, "reference is not set");
            return;
        }
        if (ws.isTestMode()) {
            HttpResponses.sendJsonError(ctx, 409, "exit TEST mode before starting production inspection");
            return;
        }

        // Не включаем gate, пока остановочный preview/отменяемый цикл ещё держит камеру.
        // Иначе первая новая группа могла бы быть пропущена как IN_FLIGHT.
        if (!clientApi.inspectionGate().awaitAllIdle(5_000L)) {
            HttpResponses.sendJsonError(ctx, 409, "camera capture is still stopping; retry start");
            return;
        }

        List<Integer> requestedCameraIds = new ArrayList<>(clientApi.inspectionGate().cameraIds());
        requestedCameraIds.sort(Integer::compareTo);
        long resumeAfterSequence = clientApi.inspectionResumeHolder() == null
                ? 0L
                : clientApi.inspectionResumeHolder().currentTriggerSequence();
        Set<Integer> changed = new LinkedHashSet<>();
        for (Integer cameraId : requestedCameraIds) {
            if (!clientApi.inspectionGate().isInspectionEnabled(cameraId)) {
                changed.add(cameraId);
            }
        }
        if (!clientApi.inspectionGate().armAllInspectionAfter(resumeAfterSequence)) {
            HttpResponses.sendJsonError(ctx, 409, "camera capture restarted while starting inspection; retry start");
            return;
        }
        sendInspectionState(ctx, requestedCameraIds, changed, Set.of(), Set.of());
    }

    private void sendInspectionState(
            HttpRequestContext ctx,
            List<Integer> requestedCameraIds,
            Set<Integer> changedCameraIds,
            Set<Integer> cancelledCameraIds
    ) throws IOException {
        sendInspectionState(ctx, requestedCameraIds, changedCameraIds, cancelledCameraIds, Set.of());
    }

    private void sendInspectionState(
            HttpRequestContext ctx,
            List<Integer> requestedCameraIds,
            Set<Integer> changedCameraIds,
            Set<Integer> cancelledCameraIds,
            Set<Integer> unknownCameraIds
    ) throws IOException {
        List<Integer> cameraIds = new ArrayList<>(clientApi.inspectionGate().cameraIds());
        cameraIds.sort(Integer::compareTo);
        Set<Integer> enabledCameraIds = new LinkedHashSet<>();
        Set<Integer> disabledCameraIds = new LinkedHashSet<>();
        for (Integer cameraId : cameraIds) {
            if (clientApi.inspectionGate().isInspectionEnabled(cameraId)) {
                enabledCameraIds.add(cameraId);
            } else {
                disabledCameraIds.add(cameraId);
            }
        }

        ObjectNode response = JSON.createObjectNode();
        response.put("ok", true);
        response.set("requestedCameraIds", toArray(requestedCameraIds));
        response.set("changedCameraIds", toArray(changedCameraIds));
        response.set("cancelledCameraIds", toArray(cancelledCameraIds));
        response.set("enabledCameraIds", toArray(enabledCameraIds));
        response.set("disabledCameraIds", toArray(disabledCameraIds));
        response.set("unknownCameraIds", toArray(unknownCameraIds));
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(response));
    }

    private String resolveAnalysisProfile(HttpRequestContext ctx) {
        String query = ctx.query();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] kv = part.split("=", 2);
            String key = urlDecode(kv[0]);
            if (!"cameraId".equals(key)) {
                continue;
            }
            String rawValue = kv.length > 1 ? urlDecode(kv[1]) : "";
            if (rawValue.isBlank()) {
                return null;
            }
            try {
                int cameraId = Integer.parseInt(rawValue);
                String profile = clientApi.analysisProfileByCamera().get(cameraId);
                return profile == null || profile.isBlank() ? null : profile;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static List<Integer> parseCameraIds(Map<String, Object> body) {
        Set<Integer> out = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Object single = body.get("cameraId");
        Integer singleId = parseCameraId(single);
        if (singleId != null) {
            out.add(singleId);
        }
        Object many = body.get("cameraIds");
        if (many instanceof Iterable<?> iterable) {
            for (Object rawId : iterable) {
                Integer cameraId = parseCameraId(rawId);
                if (cameraId != null) {
                    out.add(cameraId);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Integer parseCameraId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ArrayNode toArray(Iterable<Integer> values) {
        ArrayNode node = JSON.createArrayNode();
        for (Integer value : values) {
            node.add(value);
        }
        return node;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        handleClientApi(ctx);
    }
}
