package com.example.iml.orchestrator.integration.http.controller.clientapi;

import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.plc.PlcFinsApi;
import com.example.iml.orchestrator.integration.plc.PlcTimeoutState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET/PUT PLC timeout routes. */
final class ClientApiPlcTimeoutRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClientApiPlcTimeoutRoutes() {
    }

    static void handle(HttpRequestContext ctx, PlcFinsApi plc) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method()) && !"PUT".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (!plc.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "plc_fins disabled");
            return;
        }
        try {
            if ("GET".equalsIgnoreCase(ctx.method())) {
                sendTimeoutsResponse(ctx, plc, plc.readTimeouts());
                return;
            }
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body.timeouts required");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            Map<String, Integer> units = parseTimeoutUnits(body);
            if (units.isEmpty()) {
                HttpResponses.sendJsonError(ctx, 400, "body.timeouts required (D4400..D4405 / names)");
                return;
            }
            sendTimeoutsResponse(ctx, plc, plc.writeTimeouts(units));
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

    static void sendTimeoutsResponse(
            HttpRequestContext ctx,
            PlcFinsApi plc,
            List<PlcTimeoutState> timeouts
    ) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("enabled", plc.enabled());
        root.put("inspection_in_flight", plc.inspectionInFlight());
        root.put("inspection_enabled", plc.inspectionEnabled());
        root.put("editable", plc.manualControlEditable());
        root.put("timeouts_editable", plc.timeoutsEditable());
        root.put("signals_editable", plc.manualControlEditable());
        root.put("unit", "100ms_bcd");
        root.set("timeouts", JSON.valueToTree(timeouts));
        root.set("signals", JSON.valueToTree(plc.listSignals()));
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    static Map<String, Integer> parseTimeoutUnits(Map<String, Object> body) {
        Object raw = body.get("timeouts");
        if (raw == null) {
            raw = body;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> units = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("ok".equals(key) || "enabled".equals(key) || "editable".equals(key)
                    || "inspection_in_flight".equals(key) || "unit".equals(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                Object unitsRaw = nested.get("value_units");
                if (unitsRaw == null) {
                    unitsRaw = nested.get("valueUnits");
                }
                if (unitsRaw == null) {
                    unitsRaw = nested.get("units");
                }
                if (unitsRaw == null) {
                    unitsRaw = nested.get("value_ms");
                    if (unitsRaw instanceof Number ms) {
                        units.put(key, ms.intValue() / 100);
                        continue;
                    }
                }
                value = unitsRaw;
            }
            if (value instanceof Number number) {
                units.put(key, number.intValue());
            } else if (value != null && !String.valueOf(value).isBlank()) {
                units.put(key, Integer.parseInt(String.valueOf(value).trim()));
            }
        }
        return units;
    }
}
