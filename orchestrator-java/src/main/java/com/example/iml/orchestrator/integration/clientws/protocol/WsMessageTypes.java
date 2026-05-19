package com.example.iml.orchestrator.integration.clientws.protocol;

/**
 * Константы поля {@code type} WebSocket-протокола UI.
 */
public final class WsMessageTypes {

    public static final String CLIENT_REFERENCE_BUNDLE = "client.reference_bundle";
    public static final String CLIENT_FP_ZONES_UPDATE = "client.fp_zones_update";
    public static final String CLIENT_SET_ACTIVE_REFERENCE_VIEW = "client.set_active_reference_view";
    public static final String CLIENT_LIGHT_BRIGHTNESS = "client.light_brightness";

    public static final String SERVER_HELLO = "server.hello";
    public static final String SERVER_INSPECT_RESULT = "server.inspect_result";
    /** Живой preview-кадр (без geometry/python), в т.ч. в {@code NO_REFERENCE}. */
    public static final String SERVER_PREVIEW_FRAME = "server.preview_frame";
    public static final String SERVER_REFERENCE_BUNDLE_ACK = "server.reference_bundle_ack";
    public static final String SERVER_FP_ZONES_ACK = "server.fp_zones_ack";
    public static final String SERVER_ACTIVE_REFERENCE_VIEW_ACK = "server.active_reference_view_ack";
    public static final String SERVER_LIGHT_BRIGHTNESS_ACK = "server.light_brightness_ack";
    public static final String SERVER_STATE = "server.state";
    public static final String SERVER_ERROR = "server.error";

    private WsMessageTypes() {
    }
}
