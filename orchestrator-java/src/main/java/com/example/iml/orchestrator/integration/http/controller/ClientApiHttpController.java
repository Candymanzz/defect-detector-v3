package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.http.controller.clientapi.ClientApiGeometryRuntimeRoutes;
import com.example.iml.orchestrator.integration.http.controller.clientapi.ClientApiInspectionRoutes;
import com.example.iml.orchestrator.integration.http.controller.clientapi.ClientApiLineDirectionRoutes;
import com.example.iml.orchestrator.integration.http.controller.clientapi.ClientApiPlcRoutes;

import java.io.IOException;

public final class ClientApiHttpController implements HttpController {

    private final ClientApiMount clientApi;
    private final ClientApiGeometryRuntimeRoutes geometryRuntimeRoutes;
    private final ClientApiLineDirectionRoutes lineDirectionRoutes;
    private final ClientApiInspectionRoutes inspectionRoutes;
    private final ClientApiPlcRoutes plcRoutes;

    public ClientApiHttpController(ClientApiMount clientApi) {
        this.clientApi = clientApi;
        this.geometryRuntimeRoutes = new ClientApiGeometryRuntimeRoutes(clientApi);
        this.lineDirectionRoutes = new ClientApiLineDirectionRoutes(clientApi);
        this.inspectionRoutes = new ClientApiInspectionRoutes(clientApi);
        this.plcRoutes = new ClientApiPlcRoutes(clientApi);
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
            geometryRuntimeRoutes.handle(ctx);
            return;
        }
        if (path.equals("/api/client/line-direction")) {
            lineDirectionRoutes.handle(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/status")) {
            inspectionRoutes.handleStatus(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/clear-reference")) {
            inspectionRoutes.handleClearReference(ctx);
            return;
        }
        if (path.equals("/api/client/inspection/stop")) {
            inspectionRoutes.handleToggle(ctx, false);
            return;
        }
        if (path.equals("/api/client/inspection/start")) {
            inspectionRoutes.handleToggle(ctx, true);
            return;
        }
        if (path.equals("/api/client/plc/timeouts")
                || path.equals("/api/client/plc/status")
                || path.equals("/api/client/plc/signals")) {
            plcRoutes.handle(ctx, path);
            return;
        }
        if (!clientApi.kopcheniConfigured()) {
            HttpResponses.sendJsonError(ctx, 503, "client_api.kopcheni_base_url not set");
            return;
        }
        KopcheniHttpProxy.forward(ctx.exchange(), clientApi.kopcheniBaseUrl(), path);
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        handleClientApi(ctx);
    }
}
