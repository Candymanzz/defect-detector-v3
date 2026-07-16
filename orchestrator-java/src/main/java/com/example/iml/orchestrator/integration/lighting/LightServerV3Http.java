package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;

/**
 * HTTP LightServer.v3 (Swagger v1, {@code /swagger/v1/swagger.json}).
 * <ul>
 *   <li>COM банк: {@code POST /api/com/light} {@code { state, brightness }} — вкл/выкл</li>
 *   <li>Яркость по камере: {@code POST /api/camera-flash/pair|single}</li>
 *   <li>Банк вспышек (interval): {@code POST /api/camera-flash/bank} {@code { state }}</li>
 *   <li>Сеть MV-LE (legacy): {@code POST /api/light}</li>
 * </ul>
 */
public final class LightServerV3Http {

    public static final String PATH_COM_DEVICES = "/api/com/devices";
    public static final String PATH_COM_LIGHT = "/api/com/light";
    public static final String PATH_NETWORK_DEVICES = "/api/devices";
    public static final String PATH_NETWORK_LIGHT = "/api/light";
    public static final String PATH_CAMERA_FLASH_PAIR = "/api/camera-flash/pair";
    public static final String PATH_CAMERA_FLASH_SINGLE = "/api/camera-flash/single";
    /** Параллельный On/Off всех устройств (Ethernet + COM) для interval_flash. */
    public static final String PATH_CAMERA_FLASH_BANK = "/api/camera-flash/bank";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LightServerV3Http() {
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://127.0.0.1:5080";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl.trim();
    }

    /**
     * HTTP 2xx и поле {@code success: true} в JSON-ответе LightServer ({@code LightCommandResponse}).
     */
    public static void requireLightCommandSuccess(String endpointId, String httpMethod, String path, HttpResponse<String> response)
            throws Exception {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status / 100 != 2) {
            throw new IllegalStateException(endpointId + " " + httpMethod + " " + path + " failed status=" + status
                    + " body=" + body);
        }
        if (body.isBlank()) {
            return;
        }
        JsonNode root = MAPPER.readTree(body);
        if (root.has("success") && !root.get("success").asBoolean(false)) {
            String err = root.hasNonNull("error") ? root.get("error").asText() : body;
            throw new IllegalStateException(endpointId + " " + httpMethod + " " + path + " success=false: " + err);
        }
    }
}
