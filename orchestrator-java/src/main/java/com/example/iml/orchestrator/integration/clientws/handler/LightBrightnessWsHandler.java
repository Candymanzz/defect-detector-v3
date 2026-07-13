package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code client.light_brightness} — яркость 0…100% глобально и/или по id endpoint.
 */
public final class LightBrightnessWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        LightTriggerClient light = ctx.application().lightTriggerClient();
        if (light == null || !light.isEnabled()) {
            ctx.application().outbound().sendError(ctx.connection(), "light_disabled", "light_servers disabled");
            return;
        }
        JsonNode payload = ctx.envelope().path("payload");
        LightBrightnessUpdate update = LightBrightnessCommands.parseBrightnessUpdateFromWsPayload(payload);
        if (update.isEmpty()) {
            ctx.application().outbound().sendError(
                    ctx.connection(),
                    "invalid_payload",
                    "payload.brightness_percent and/or payload.endpoints required (0..100)"
            );
            return;
        }
        LightBrightnessUpdate.apply(light, update);
        ctx.application().outbound().sendLightBrightnessAck(
                ctx.connection(), ctx.envelope(), light.brightnessByEndpoint(), light.brightnessPercent());
    }
}
