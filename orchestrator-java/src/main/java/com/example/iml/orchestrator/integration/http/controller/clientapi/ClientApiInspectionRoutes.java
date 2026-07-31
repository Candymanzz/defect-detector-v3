package com.example.iml.orchestrator.integration.http.controller.clientapi;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.http.HttpJsonCameraIds;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientApiInspectionRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClientApiMount clientApi;

    public ClientApiInspectionRoutes(ClientApiMount clientApi) {
        this.clientApi = clientApi;
    }

    public void handleStatus(HttpRequestContext ctx) throws IOException {
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

    public void handleClearReference(HttpRequestContext ctx) throws IOException {
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

    public void handleToggle(HttpRequestContext ctx, boolean enabled) throws IOException {
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
        List<Integer> requestedCameraIds = HttpJsonCameraIds.parse(body);
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
            } else if (clientApi.inspectionGate().disableInspectionAndRequestCancel(cameraId)) {
                cancelled.add(cameraId);
            }
        }

        sendInspectionState(ctx, requestedCameraIds, changed, cancelled, unknown);
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

    private static ArrayNode toArray(Iterable<Integer> values) {
        ArrayNode node = JSON.createArrayNode();
        for (Integer value : values) {
            node.add(value);
        }
        return node;
    }
}
