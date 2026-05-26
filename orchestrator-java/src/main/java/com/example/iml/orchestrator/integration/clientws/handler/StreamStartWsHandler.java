package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code client.stream_start} — запуск видеопотока с камеры (worker continuous + JPEG preview).
 */
public final class StreamStartWsHandler implements WsMessageHandler {

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
        int maxFps = YamlScalars.toInt(payload.path("max_fps"), 0);
        ClientStreamConfig cfg = ctx.application().clientStreamConfig();
        int fps = cfg == null ? maxFps : cfg.clampFps(maxFps > 0 ? maxFps : cfg.defaultMaxFps());
        try {
            streams.start(cameraId, fps, ctx.connection());
        } catch (IllegalStateException e) {
            ctx.application().outbound().sendError(ctx.connection(), "stream_already_active", e.getMessage());
        } catch (Exception e) {
            ctx.application().outbound().sendError(ctx.connection(), "stream_start_failed", e.getMessage());
        }
    }
}
