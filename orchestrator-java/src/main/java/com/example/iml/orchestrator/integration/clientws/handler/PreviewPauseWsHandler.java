package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;

/**
 * {@code client.preview_pause} — pauses live preview capture/publish.
 */
public final class PreviewPauseWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        LivePreviewGate gate = ctx.application().livePreviewGate();
        if (gate != null) {
            gate.setPaused(true);
            ctx.application().log().info("client_ws preview paused");
        }
    }
}
