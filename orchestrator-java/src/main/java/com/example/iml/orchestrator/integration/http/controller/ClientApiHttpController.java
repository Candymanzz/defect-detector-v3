package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
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
            HttpResponses.corsPreflight(ctx.exchange(), "GET, POST, PUT, DELETE, OPTIONS");
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
        if (path.equals("/api/client/inspection/stop")) {
            handleStopInspection(ctx);
            return;
        }
        if (!clientApi.kopcheniConfigured()) {
            HttpResponses.sendJsonError(ctx, 503, "client_api.kopcheni_base_url not set");
            return;
        }
        KopcheniHttpProxy.forward(ctx.exchange(), clientApi.kopcheniBaseUrl(), path);
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
        if ("DELETE".equalsIgnoreCase(m)) {
            clientApi.geometryRuntime().clear(analysisProfile);
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void handleStopInspection(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (clientApi.inspectionGate() == null) {
            HttpResponses.sendJsonError(ctx, 503, "inspection gate not configured");
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

        Set<Integer> stopped = new LinkedHashSet<>();
        Set<Integer> alreadyIdle = new LinkedHashSet<>();
        Set<Integer> unknown = new LinkedHashSet<>();
        for (Integer cameraId : requestedCameraIds) {
            if (!clientApi.inspectionGate().isKnownCamera(cameraId)) {
                unknown.add(cameraId);
                continue;
            }
            boolean cancelled = clientApi.inspectionGate().requestCancel(cameraId);
            if (cancelled) {
                stopped.add(cameraId);
            } else {
                alreadyIdle.add(cameraId);
            }
        }

        ObjectNode response = JSON.createObjectNode();
        response.put("ok", true);
        response.set("requestedCameraIds", toArray(requestedCameraIds));
        response.set("stoppedCameraIds", toArray(stopped));
        response.set("alreadyIdleCameraIds", toArray(alreadyIdle));
        response.set("unknownCameraIds", toArray(unknown));
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
