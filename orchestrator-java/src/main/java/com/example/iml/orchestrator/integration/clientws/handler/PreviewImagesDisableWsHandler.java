package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;

/**
 * Keeps preview captures and frame metadata flowing, but disables JPEG generation.
 */
public final class PreviewImagesDisableWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        LivePreviewGate gate = ctx.application().livePreviewGate();
        if (gate != null) {
            gate.setImagesEnabled(false);
            ctx.application().log().info("client_ws preview images disabled");
        }
    }
}
