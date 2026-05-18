import { appEnv } from "../config/env";
import type {
  ClientFpZonesUpdatePayload,
  ClientReferenceBundlePayload,
  ClientSetActiveReferenceViewPayload,
  ClientWsEnvelope,
  ClientWsPayloadByType,
  ServerWsMessage,
  WsConnectionStatus,
  WsMessageHandler,
  WsStatusHandler,
} from "./types";

const PROTOCOL_VERSION = 1;

export class OrchestratorWebSocketClient {
  private socket: WebSocket | null = null;
  private reconnectTimerId: number | null = null;
  private reconnectAttempt = 0;
  private manuallyClosed = false;
  private readonly messageHandlers = new Set<WsMessageHandler>();
  private readonly statusHandlers = new Set<WsStatusHandler>();
  private status: WsConnectionStatus = {
    state: "idle",
    reconnectAttempt: 0,
  };

  constructor(private readonly url: string) {}

  get snapshot() {
    return this.status;
  }

  get isOpen() {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  connect() {
    if (
      this.socket?.readyState === WebSocket.CONNECTING ||
      this.socket?.readyState === WebSocket.OPEN
    ) {
      return;
    }

    this.manuallyClosed = false;
    this.clearReconnectTimer();
    this.setStatus({
      state: this.reconnectAttempt > 0 ? "reconnecting" : "connecting",
      reconnectAttempt: this.reconnectAttempt,
    });

    const socket = new WebSocket(this.url);
    this.socket = socket;

    socket.addEventListener("open", () => {
      this.reconnectAttempt = 0;
      this.setStatus({
        state: "open",
        reconnectAttempt: 0,
      });
    });

    socket.addEventListener("message", (event) => {
      this.handleMessage(event.data);
    });

    socket.addEventListener("error", () => {
      this.setStatus({
        state: "error",
        reconnectAttempt: this.reconnectAttempt,
        lastError: "websocket error",
      });
    });

    socket.addEventListener("close", (event) => {
      if (this.socket === socket) {
        this.socket = null;
      }

      if (this.manuallyClosed) {
        this.setStatus({
          state: "closed",
          reconnectAttempt: this.reconnectAttempt,
          closeCode: event.code,
          closeReason: event.reason,
        });
        return;
      }

      this.scheduleReconnect(event);
    });
  }

  disconnect(code = 1000, reason = "client_disconnect") {
    this.manuallyClosed = true;
    this.clearReconnectTimer();
    this.socket?.close(code, reason);
    this.socket = null;
    this.setStatus({
      state: "closed",
      reconnectAttempt: this.reconnectAttempt,
      closeCode: code,
      closeReason: reason,
    });
  }

  onMessage(handler: WsMessageHandler) {
    this.messageHandlers.add(handler);
    return () => this.messageHandlers.delete(handler);
  }

  onStatus(handler: WsStatusHandler) {
    this.statusHandlers.add(handler);
    handler(this.status);
    return () => this.statusHandlers.delete(handler);
  }

  sendReferenceBundle(payload: ClientReferenceBundlePayload) {
    return this.send("client.reference_bundle", payload);
  }

  sendFpZonesUpdate(payload: Omit<ClientFpZonesUpdatePayload, "protocol_version">) {
    return this.send("client.fp_zones_update", {
      ...payload,
      protocol_version: PROTOCOL_VERSION,
    });
  }

  sendActiveReferenceView(payload: Omit<ClientSetActiveReferenceViewPayload, "protocol_version">) {
    return this.send("client.set_active_reference_view", {
      ...payload,
      protocol_version: PROTOCOL_VERSION,
    });
  }

  send<TType extends keyof ClientWsPayloadByType>(type: TType, payload: ClientWsPayloadByType[TType]) {
    const message: ClientWsEnvelope<TType> = {
      type,
      protocol_version: PROTOCOL_VERSION,
      message_id: createMessageId(),
      payload,
    };

    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("WebSocket is not open");
    }

    this.socket.send(JSON.stringify(message));
    return message.message_id;
  }

  private handleMessage(data: unknown) {
    if (typeof data !== "string") {
      this.setStatus({
        ...this.status,
        lastError: "received non-text websocket message",
      });
      return;
    }

    try {
      const message = normalizeServerMessage(JSON.parse(data));
      this.setStatus({
        ...this.status,
        lastMessageType: message.type,
        lastMessageAtMs: Date.now(),
        lastError: undefined,
      });

      for (const handler of this.messageHandlers) {
        handler(message);
      }
    } catch (error) {
      this.setStatus({
        ...this.status,
        lastError: error instanceof Error ? error.message : "invalid websocket json",
      });
    }
  }

  private scheduleReconnect(event: CloseEvent) {
    this.reconnectAttempt += 1;
    const delayMs = Math.min(30000, 500 * 2 ** Math.min(this.reconnectAttempt - 1, 6));

    this.setStatus({
      state: "reconnecting",
      reconnectAttempt: this.reconnectAttempt,
      reconnectInMs: delayMs,
      closeCode: event.code,
      closeReason: event.reason,
    });

    this.reconnectTimerId = window.setTimeout(() => {
      this.reconnectTimerId = null;
      this.connect();
    }, delayMs);
  }

  private clearReconnectTimer() {
    if (this.reconnectTimerId !== null) {
      window.clearTimeout(this.reconnectTimerId);
      this.reconnectTimerId = null;
    }
  }

  private setStatus(status: WsConnectionStatus) {
    this.status = status;

    for (const handler of this.statusHandlers) {
      handler(status);
    }
  }
}

export const orchestratorWs = new OrchestratorWebSocketClient(appEnv.wsUrl);

function normalizeServerMessage(parsed: unknown): ServerWsMessage {
  if (!parsed || typeof parsed !== "object") {
    return {
      type: "server.unknown",
      payload: parsed,
    };
  }

  const candidate = parsed as { type?: unknown };

  if (typeof candidate.type !== "string") {
    return {
      type: "server.unknown",
      payload: parsed,
    };
  }

  if (
    candidate.type === "server.hello" ||
    candidate.type === "server.state" ||
    candidate.type === "server.inspect_result" ||
    candidate.type === "server.reference_bundle_ack" ||
    candidate.type === "server.fp_zones_ack" ||
    candidate.type === "server.active_reference_view_ack" ||
    candidate.type === "server.error"
  ) {
    return parsed as ServerWsMessage;
  }

  return {
    type: "server.unknown",
    payload: parsed,
  };
}

function createMessageId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
