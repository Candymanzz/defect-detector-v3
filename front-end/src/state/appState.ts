import type { UiCameraList } from "../api";
import type { LightBrightnessSettings } from "../api/types";
import type { InspectResultPayload, ServerWsMessage, WsConnectionStatus, WsSessionState } from "../ws";

export type LoadStatus = "idle" | "loading" | "ready" | "error";

export type BackendState = {
  health: LoadStatus;
  healthText: string;
  cameras: UiCameraList;
  camerasStatus: LoadStatus;
  lastError: string | null;
};

export type WsState = {
  connection: WsConnectionStatus;
  session: WsSessionState | "unknown";
  lastEvent: ServerWsMessage["type"] | "none";
  lastInspectResult: InspectResultPayload | null;
  lastError: string | null;
  lastSentMessageId: string | null;
};

export type LightState = {
  endpoint: string;
  status: LoadStatus | "saving";
  settings: LightBrightnessSettings | null;
  lastError: string | null;
};

export type AppState = {
  backend: BackendState;
  ws: WsState;
  light: LightState;
};

type StateListener = (state: AppState) => void;
type AppStatePatch = {
  backend?: Partial<BackendState>;
  ws?: Partial<WsState>;
  light?: Partial<LightState>;
};

const initialState: AppState = {
  backend: {
    health: "idle",
    healthText: "",
    cameras: {
      cameras: [],
    },
    camerasStatus: "idle",
    lastError: null,
  },
  ws: {
    connection: {
      state: "idle",
      reconnectAttempt: 0,
    },
    session: "unknown",
    lastEvent: "none",
    lastInspectResult: null,
    lastError: null,
    lastSentMessageId: null,
  },
  light: {
    endpoint: "",
    status: "idle",
    settings: null,
    lastError: null,
  },
};

class AppStore {
  private state: AppState = structuredClone(initialState);
  private readonly listeners = new Set<StateListener>();

  get snapshot() {
    return this.state;
  }

  subscribe(listener: StateListener) {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  patch(patch: AppStatePatch) {
    this.state = {
      ...this.state,
      ...patch,
      backend: {
        ...this.state.backend,
        ...patch.backend,
      },
      ws: {
        ...this.state.ws,
        ...patch.ws,
      },
      light: {
        ...this.state.light,
        ...patch.light,
      },
    };
    this.emit();
  }

  private emit() {
    for (const listener of this.listeners) {
      listener(this.state);
    }
  }
}

export const appStore = new AppStore();

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}
