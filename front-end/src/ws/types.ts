export type WsConnectionState = "idle" | "connecting" | "open" | "reconnecting" | "error" | "closed";

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

export type ServerWsEnvelope<TType extends string, TPayload> = {
  type: TType;
  protocol_version: number;
  message_id: string;
  payload: TPayload;
};

export type ClientWsEnvelope<TType extends keyof ClientWsPayloadByType> = {
  type: TType;
  protocol_version: 1;
  message_id: string;
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
    code: string;
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
  frame_id: string;
  shm_name: string;
  width: number;
  height: number;
  stride: number;
  shm_offset: number;
  pixel_format: string;
  channels: number;
  expires_at_ms?: number;
  ttl_ms?: number;
  read_token?: string;
};

export type HeatmapDescriptor = {
  width: number;
  height: number;
  pixel_format: "gray_u8" | string;
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
  points_norm_heatmap: Array<{
    x: number;
    y: number;
  }>;
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
  protocol_version: 1;
  heatmap_width: number;
  heatmap_height: number;
  fp_zones: FpZoneNorm[];
};

export type ClientSetActiveReferenceViewPayload = {
  protocol_version: 1;
  view_index: number;
};

export type ClientWsPayloadByType = {
  "client.reference_bundle": ClientReferenceBundlePayload;
  "client.fp_zones_update": ClientFpZonesUpdatePayload;
  "client.set_active_reference_view": ClientSetActiveReferenceViewPayload;
};
