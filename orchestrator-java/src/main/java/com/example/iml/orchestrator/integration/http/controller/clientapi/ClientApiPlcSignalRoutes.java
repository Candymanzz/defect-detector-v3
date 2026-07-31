package com.example.iml.orchestrator.integration.http.controller.clientapi;

import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.plc.PlcFinsApi;
import com.example.iml.orchestrator.integration.plc.PlcSignalState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET/POST {@code /api/client/plc/signals}. */
final class ClientApiPlcSignalRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClientApiPlcSignalRoutes() {
    }

    static void handle(HttpRequestContext ctx, PlcFinsApi plc) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method()) && !"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (!plc.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "plc_fins disabled");
            return;
        }
        try {
            if ("GET".equalsIgnoreCase(ctx.method())) {
                sendSignalsResponse(ctx, plc, plc.listSignals());
                return;
            }
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body required");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            Map<String, Boolean> values = new LinkedHashMap<>();
            Map<String, Boolean> pulses = new LinkedHashMap<>();
            Object signalName = body.get("signal");
            if (signalName != null) {
                String name = String.valueOf(signalName).trim();
                values.put(name, toBool(body.get("value"), true));
                pulses.put(name, toBool(body.get("pulse"), false));
            }
            Object signalsRaw = body.get("signals");
            if (signalsRaw instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String name = String.valueOf(entry.getKey()).trim();
                    Object value = entry.getValue();
                    if (value instanceof Map<?, ?> nested) {
                        Object nestedValue = nested.get("value");
                        values.put(name, toBool(nestedValue, true));
                        pulses.put(name, toBool(nested.get("pulse"), false));
                    } else {
                        values.put(name, toBool(value, true));
                    }
                }
            }
            Object pulseMapRaw = body.get("pulse");
            if (pulseMapRaw instanceof Map<?, ?> pulseMap && signalName == null) {
                for (Map.Entry<?, ?> entry : pulseMap.entrySet()) {
                    pulses.put(String.valueOf(entry.getKey()).trim(), toBool(entry.getValue(), false));
                }
            }
            if (values.isEmpty()) {
                HttpResponses.sendJsonError(ctx, 400, "body.signal or body.signals required");
                return;
            }
            sendSignalsResponse(ctx, plc, plc.writeSignals(values, pulses));
        } catch (IllegalStateException e) {
            HttpResponses.sendJsonError(ctx, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJsonError(ctx, 400, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpResponses.sendJsonError(ctx, 503, "plc fins interrupted");
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static boolean toBool(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text);
    }

    static void sendSignalsResponse(
            HttpRequestContext ctx,
            PlcFinsApi plc,
            List<PlcSignalState> signals
    ) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("enabled", plc.enabled());
        root.put("inspection_in_flight", plc.inspectionInFlight());
        root.put("inspection_enabled", plc.inspectionEnabled());
        root.put("editable", plc.manualControlEditable());
        root.put("timeouts_editable", plc.timeoutsEditable());
        root.put("signals_editable", plc.manualControlEditable());
        root.set("signals", JSON.valueToTree(signals));
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }
}
