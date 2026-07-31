package com.example.iml.orchestrator.integration.http.controller.clientapi;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public final class ClientApiPlcRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClientApiMount clientApi;

    public ClientApiPlcRoutes(ClientApiMount clientApi) {
        this.clientApi = clientApi;
    }

    public void handle(HttpRequestContext ctx, String path) throws IOException {
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
            ClientApiPlcSignalRoutes.handle(ctx, plc);
            return;
        }
        ClientApiPlcTimeoutRoutes.handle(ctx, plc);
    }
}
