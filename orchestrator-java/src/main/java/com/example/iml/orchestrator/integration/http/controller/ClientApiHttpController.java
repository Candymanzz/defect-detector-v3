package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
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
                HttpResponses.sendJsonError(ctx, 400, "body.timeouts required (D4400..D4404 or names)");
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
            }
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
