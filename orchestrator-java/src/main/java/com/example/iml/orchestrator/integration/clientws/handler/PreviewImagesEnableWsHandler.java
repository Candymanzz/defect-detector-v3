package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;

/**
 * Enables JPEG generation for preview consumers such as reference setup.
 */
public final class PreviewImagesEnableWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        LivePreviewGate gate = ctx.application().livePreviewGate();
        if (gate != null) {
            gate.setImagesEnabled(true);
            ctx.application().log().info("client_ws preview images enabled");
        }
    }
}
