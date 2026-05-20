export type WsConnectionState = "idle" | "connecting" | "open" | "reconnecting" | "error" | "closed";
export type WsProtocolVersion = 1;
export type WsMessageId = string;

export type ClientWsMessageType =
  | "client.reference_bundle"
  | "client.fp_zones_update"
  | "client.set_active_reference_view";

export type ServerWsMessageType =
  | "server.hello"
  | "server.state"
  | "server.inspect_result"
  | "server.reference_bundle_ack"
  | "server.fp_zones_ack"
  | "server.active_reference_view_ack"
  | "server.error";

export type WsConnectionStatus = {
  state: WsConnectionState;
  reconnectAttempt: number;
  reconnectInMs?: number;
  closeCode?: number;
  closeReason?: string;
  lastError?: string;
  lastMessageType?: ServerWsMessage["type"];
  lastMessageAtMs?: number;
};

export type WsSessionState = "NO_REFERENCE" | "READY" | "OPERATIONAL";
export type ServerErrorCode =
  | "parse_error"
  | "no_reference"
  | "invalid_payload"
  | "invalid_protocol_version"
  | "invalid_heatmap_size"
  | "invalid_view_index"
  | "invalid_product_type"
  | "invalid_joint_view_index"
  | "invalid_views"
  | "invalid_view"
  | "invalid_frame"
  | "invalid_camera_id"
  | "invalid_frame_id"
  | "invalid_shm_name"
  | "invalid_frame_size"
  | "invalid_shm_offset"
  | "invalid_stride"
  | "invalid_interest_roi"
  | "invalid_joint_roi"
  | "missing_joint_roi"
  | "invalid_fp_zones"
  | "invalid_fp_zone"
  | "invalid_fp_polygon"
  | "invalid_fp_point"
  | "fp_point_out_of_range"
  | "kopcheni_sync_failed"
  | "error"
  | string;

export type ServerWsEnvelope<TType extends string, TPayload> = {
  type: TType;
  protocol_version: number;
  message_id: WsMessageId;
  payload: TPayload;
};

export type ClientWsEnvelope<TType extends ClientWsMessageType> = {
  type: TType;
  protocol_version: WsProtocolVersion;
  message_id: WsMessageId;
  payload: ClientWsPayloadByType[TType];
};

export type ServerHelloMessage = ServerWsEnvelope<
  "server.hello",
  {
    session_state: WsSessionState;
    server_ts_ms: number;
  }
>;

export type ServerStateMessage = ServerWsEnvelope<
  "server.state",
  {
    session_state: WsSessionState;
    server_ts_ms: number;
  }
>;

export type ServerInspectResultMessage = ServerWsEnvelope<"server.inspect_result", InspectResultPayload>;

export type ServerReferenceBundleAckMessage = ServerWsEnvelope<
  "server.reference_bundle_ack",
  {
    ok: boolean;
  }
>;

export type ServerFpZonesAckMessage = ServerWsEnvelope<
  "server.fp_zones_ack",
  {
    ok: boolean;
    server_ts_ms: number;
  }
>;

export type ServerActiveReferenceViewAckMessage = ServerWsEnvelope<
  "server.active_reference_view_ack",
  {
    ok: boolean;
    view_index: number;
    server_ts_ms: number;
  }
>;

export type ServerErrorMessage = ServerWsEnvelope<
  "server.error",
  {
    code: ServerErrorCode;
    message: string;
  }
>;

export type UnknownServerWsMessage = {
  type: "server.unknown";
  protocol_version?: number;
  message_id?: string;
  payload: unknown;
};

export type ServerWsMessage =
  | ServerHelloMessage
  | ServerStateMessage
  | ServerInspectResultMessage
  | ServerReferenceBundleAckMessage
  | ServerFpZonesAckMessage
  | ServerActiveReferenceViewAckMessage
  | ServerErrorMessage
  | UnknownServerWsMessage;

export type ServerWsPayloadByType = {
  "server.hello": ServerHelloMessage["payload"];
  "server.state": ServerStateMessage["payload"];
  "server.inspect_result": InspectResultPayload;
  "server.reference_bundle_ack": ServerReferenceBundleAckMessage["payload"];
  "server.fp_zones_ack": ServerFpZonesAckMessage["payload"];
  "server.active_reference_view_ack": ServerActiveReferenceViewAckMessage["payload"];
  "server.error": ServerErrorMessage["payload"];
};

export type WsMessageHandler = (message: ServerWsMessage) => void;
export type WsStatusHandler = (status: WsConnectionStatus) => void;

export type InspectResultPayload = {
  camera_id: number;
  frame_id: string;
  session_state: WsSessionState;
  current: ShmFrameRefData;
  heatmap: HeatmapDescriptor | null;
  active_reference_view_index: number;
  detector: {
    detector_id?: string;
    product_type?: string;
  };
  fp_zones: FpZoneNorm[];
  fp_coordinate_space?: {
    heatmap_width: number;
    heatmap_height: number;
  };
  server_ts_ms: number;
};

export type ShmFrameRefData = {
  camera_id: number;
  frame_id: string | number;
  shm_name: string;
  width: number;
  height: number;
  stride: number;
  shm_offset: number;
  pixel_format: PixelFormat;
  channels: number;
  expires_at_ms?: number;
  ttl_ms?: number;
  read_token?: string;
};

export type PixelFormat = "bgr_u8" | "gray_u8" | string;
export type HeatmapPixelFormat = "gray_u8" | string;

export type HeatmapDescriptor = {
  width: number;
  height: number;
  pixel_format: HeatmapPixelFormat;
  channels: number;
  artifact_id?: string;
  http_path?: string;
  file_path?: string;
};

export type PixelRoi = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type FpZoneNorm = {
  id?: string;
  note: string;
  points_norm_heatmap: FpPointNorm[];
};

export type FpPointNorm = {
  x: number;
  y: number;
};

export type ReferenceViewSlot = {
  frame: ShmFrameRefData;
  interest_roi: PixelRoi;
  joint_roi?: PixelRoi | null;
};

export type ClientReferenceBundlePayload = {
  product_type: string;
  joint_view_index: number;
  heatmap_width: number;
  heatmap_height: number;
  views: [ReferenceViewSlot, ReferenceViewSlot, ReferenceViewSlot, ReferenceViewSlot, ReferenceViewSlot];
  fp_zones: FpZoneNorm[];
};

export type ClientFpZonesUpdatePayload = {
  protocol_version: WsProtocolVersion;
  heatmap_width: number;
  heatmap_height: number;
  fp_zones: FpZoneNorm[];
};

export type ClientSetActiveReferenceViewPayload = {
  protocol_version: WsProtocolVersion;
  view_index: number;
};

export type ClientWsPayloadByType = {
  "client.reference_bundle": ClientReferenceBundlePayload;
  "client.fp_zones_update": ClientFpZonesUpdatePayload;
  "client.set_active_reference_view": ClientSetActiveReferenceViewPayload;
};

export type ClientWsMessage =
  | ClientWsEnvelope<"client.reference_bundle">
  | ClientWsEnvelope<"client.fp_zones_update">
  | ClientWsEnvelope<"client.set_active_reference_view">;
