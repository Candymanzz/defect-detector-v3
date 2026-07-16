package com.example.iml.orchestrator.integration.clientws.protocol;

/**
 * Константы поля {@code type} WebSocket-протокола UI.
 */
public final class WsMessageTypes {

    public static final String CLIENT_REFERENCE_BUNDLE = "client.reference_bundle";
    public static final String CLIENT_FP_ZONES_UPDATE = "client.fp_zones_update";
    public static final String CLIENT_SET_ACTIVE_REFERENCE_VIEW = "client.set_active_reference_view";
    public static final String CLIENT_LIGHT_BRIGHTNESS = "client.light_brightness";
    public static final String CLIENT_STREAM_START = "client.stream_start";
    public static final String CLIENT_STREAM_STOP = "client.stream_stop";
    public static final String CLIENT_PREVIEW_PAUSE = "client.preview_pause";
    public static final String CLIENT_PREVIEW_RESUME = "client.preview_resume";
    public static final String CLIENT_PREVIEW_IMAGES_DISABLE = "client.preview_images_disable";
    public static final String CLIENT_PREVIEW_IMAGES_ENABLE = "client.preview_images_enable";

    public static final String SERVER_HELLO = "server.hello";
    public static final String SERVER_INSPECT_RESULT = "server.inspect_result";
    public static final String SERVER_INSPECT_BUCKET_RESULT = "server.inspect_bucket_result";
    /** Живой preview-кадр (без geometry/python), в т.ч. в {@code NO_REFERENCE}. */
    public static final String SERVER_PREVIEW_FRAME = "server.preview_frame";
    public static final String SERVER_PREVIEW_BATCH = "server.preview_batch";
    public static final String SERVER_STREAM_STARTED = "server.stream_started";
    public static final String SERVER_STREAM_STOPPED = "server.stream_stopped";
    public static final String SERVER_REFERENCE_BUNDLE_ACK = "server.reference_bundle_ack";
    public static final String SERVER_FP_ZONES_ACK = "server.fp_zones_ack";
    public static final String SERVER_ACTIVE_REFERENCE_VIEW_ACK = "server.active_reference_view_ack";
    public static final String SERVER_LIGHT_BRIGHTNESS_ACK = "server.light_brightness_ack";
    public static final String SERVER_PLC_FINS_TRAFFIC = "server.plc_fins_traffic";
    public static final String SERVER_STATE = "server.state";
    public static final String SERVER_ERROR = "server.error";

    private WsMessageTypes() {
    }
}
