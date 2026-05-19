package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code client.light_brightness} — установка яркости вспышки 0…100% (тот же runtime, что HTTP PUT).
 */
public final class LightBrightnessWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        LightTriggerClient light = ctx.application().lightTriggerClient();
        if (light == null) {
            ctx.application().outbound().sendError(ctx.connection(), "light_disabled", "light_servers disabled");
            return;
        }
        JsonNode payload = ctx.envelope().path("payload");
        Integer percent = LightBrightnessCommands.parseUnifiedPercentFromWsPayload(payload);
        if (percent == null) {
            ctx.application().outbound().sendError(
                    ctx.connection(),
                    "invalid_payload",
                    "payload.brightness_percent required (0..100)"
            );
            return;
        }
        light.setBrightnessPercent(percent);
        ctx.application().outbound().sendLightBrightnessAck(ctx.connection(), ctx.envelope(), light.brightnessPercent());
    }
}
