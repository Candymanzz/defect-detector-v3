package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.fasterxml.jackson.databind.JsonNode;

/** {@code client.stream_stop} — остановка видеопотока, восстановление software trigger в worker. */
public final class StreamStopWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        CameraStreamService streams = ctx.application().cameraStreamService();
        if (streams == null) {
            ctx.application().outbound().sendError(ctx.connection(), "stream_disabled", "client stream not configured");
            return;
        }
        JsonNode payload = ctx.envelope().path("payload");
        int cameraId = YamlScalars.toInt(payload.path("camera_id"), -1);
        if (cameraId < 0) {
            ctx.application().outbound().sendError(ctx.connection(), "invalid_payload", "payload.camera_id required");
            return;
        }
        streams.stop(cameraId);
    }
}
