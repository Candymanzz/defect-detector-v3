import "./style.css";
import { orchestratorApi } from "./api";
import { appEnv } from "./config/env";
import { orchestratorWs } from "./ws";
import type {
  ClientReferenceBundlePayload,
  FpZoneNorm,
  ServerWsMessage,
  WsConnectionStatus,
} from "./ws";

type OutboundWsType =
  | "client.reference_bundle"
  | "client.fp_zones_update"
  | "client.set_active_reference_view";

const app = document.querySelector<HTMLDivElement>("#app");

if (!app) {
  throw new Error("Application root was not found");
}

app.innerHTML = `
  <main class="workspace">
    <section class="hero">
      <p class="eyebrow">desktop client</p>
      <h1>Defect Detector</h1>
      <p class="lead">
        Vite renderer is ready, Electron preload is connected, and the app shell can now talk to the desktop process.
      </p>
    </section>

    <section class="status-panel" aria-label="Runtime status">
      <div class="status-row">
        <span>Renderer</span>
        <strong>Vite</strong>
      </div>
      <div class="status-row">
        <span>Electron bridge</span>
        <strong id="bridge-status">checking</strong>
      </div>
      <div class="status-row">
        <span>Mode</span>
        <strong id="runtime-mode">unknown</strong>
      </div>
      <div class="status-row">
        <span>HTTP API</span>
        <strong id="api-base-url">unknown</strong>
      </div>
      <div class="status-row">
        <span>Backend health</span>
        <strong id="backend-health">checking</strong>
      </div>
      <div class="status-row">
        <span>Cameras</span>
        <strong id="camera-count">checking</strong>
      </div>
      <div class="status-row">
        <span>WebSocket</span>
        <strong id="ws-url">unknown</strong>
      </div>
      <div class="status-row">
        <span>WS connection</span>
        <strong id="ws-connection">idle</strong>
      </div>
      <div class="status-row">
        <span>WS session</span>
        <strong id="ws-session">unknown</strong>
      </div>
      <div class="status-row">
        <span>WS last event</span>
        <strong id="ws-last-event">none</strong>
      </div>
      <div class="status-row">
        <span>Platform</span>
        <strong id="runtime-platform">unknown</strong>
      </div>
    </section>

    <section class="ws-outbox" aria-label="WebSocket outbox">
      <div class="section-heading">
        <p class="eyebrow">websocket</p>
        <h2>Outbound message</h2>
      </div>

      <div class="control-grid">
        <label class="field">
          <span>Type</span>
          <select id="ws-message-type">
            <option value="client.reference_bundle">reference bundle</option>
            <option value="client.fp_zones_update">FP zones update</option>
            <option value="client.set_active_reference_view">active reference view</option>
          </select>
        </label>

        <label class="field">
          <span>Active view</span>
          <input id="ws-active-view" type="number" min="0" max="4" step="1" value="0" />
        </label>
      </div>

      <label class="field field-wide">
        <span>Payload JSON</span>
        <textarea id="ws-payload" spellcheck="false"></textarea>
      </label>

      <div class="button-row">
        <button id="ws-send" type="button">Send</button>
        <button id="ws-example" type="button" class="secondary">Example</button>
      </div>

      <div class="status-row outbox-status">
        <span>Last sent</span>
        <strong id="ws-last-sent">none</strong>
      </div>
    </section>
  </main>
`;

const bridgeStatus = document.querySelector<HTMLElement>("#bridge-status");
const runtimeMode = document.querySelector<HTMLElement>("#runtime-mode");
const runtimePlatform = document.querySelector<HTMLElement>("#runtime-platform");
const apiBaseUrl = document.querySelector<HTMLElement>("#api-base-url");
const wsUrl = document.querySelector<HTMLElement>("#ws-url");
const backendHealth = document.querySelector<HTMLElement>("#backend-health");
const cameraCount = document.querySelector<HTMLElement>("#camera-count");
const wsConnection = document.querySelector<HTMLElement>("#ws-connection");
const wsSession = document.querySelector<HTMLElement>("#ws-session");
const wsLastEvent = document.querySelector<HTMLElement>("#ws-last-event");
const wsMessageType = document.querySelector<HTMLSelectElement>("#ws-message-type");
const wsPayload = document.querySelector<HTMLTextAreaElement>("#ws-payload");
const wsActiveView = document.querySelector<HTMLInputElement>("#ws-active-view");
const wsSend = document.querySelector<HTMLButtonElement>("#ws-send");
const wsExample = document.querySelector<HTMLButtonElement>("#ws-example");
const wsLastSent = document.querySelector<HTMLElement>("#ws-last-sent");

async function loadRuntimeInfo() {
  const api = window.electronAPI;

  if (!api) {
    setText(bridgeStatus, "browser only");
    setText(runtimeMode, appEnv.mode);
    setText(runtimePlatform, navigator.platform);
    setText(apiBaseUrl, appEnv.apiBaseUrl);
    setText(wsUrl, appEnv.wsUrl);
    return;
  }

  const environment = await api.getEnvironment();

  setText(bridgeStatus, `connected (${environment.versions.electron ?? "Electron"})`);
  setText(runtimeMode, environment.mode);
  setText(runtimePlatform, environment.platform);
  setText(apiBaseUrl, appEnv.apiBaseUrl);
  setText(wsUrl, appEnv.wsUrl);
}

function setText(element: HTMLElement | null, value: string) {
  if (element) {
    element.textContent = value;
  }
}

loadRuntimeInfo().catch((error) => {
  setText(bridgeStatus, "error");
  console.error(error);
});

loadBackendSummary().catch((error) => {
  setText(backendHealth, "offline");
  setText(cameraCount, "unavailable");
  console.error(error);
});

async function loadBackendSummary() {
  const health = await orchestratorApi.health();
  setText(backendHealth, health.trim() || "ok");

  const cameraList = await orchestratorApi.listCameras();
  setText(cameraCount, String(cameraList.cameras.length));
}

orchestratorWs.onStatus((status) => {
  setText(wsConnection, formatWsStatus(status));
  updateWsSendButton();
});

orchestratorWs.onMessage((message) => {
  setText(wsLastEvent, message.type);
  updateSessionState(message);
});

orchestratorWs.connect();
setupWsOutbox();

window.addEventListener("beforeunload", () => {
  orchestratorWs.disconnect();
});

function updateSessionState(message: ServerWsMessage) {
  if (message.type === "server.hello" || message.type === "server.state") {
    setText(wsSession, message.payload.session_state);
    return;
  }

  if (message.type === "server.inspect_result") {
    setText(wsSession, message.payload.session_state);
    return;
  }

  if (message.type === "server.error") {
    setText(wsSession, message.payload.code);
  }
}

function formatWsStatus(status: WsConnectionStatus) {
  if (status.state === "reconnecting" && status.reconnectInMs) {
    return `reconnect ${Math.ceil(status.reconnectInMs / 1000)}s`;
  }

  if (status.state === "error" && status.lastError) {
    return "error";
  }

  return status.state;
}

function setupWsOutbox() {
  fillWsPayloadExample();
  updateWsSendButton();

  wsMessageType?.addEventListener("change", () => {
    fillWsPayloadExample();
  });

  wsExample?.addEventListener("click", () => {
    fillWsPayloadExample();
  });

  wsSend?.addEventListener("click", () => {
    sendWsMessageFromForm();
  });
}

function sendWsMessageFromForm() {
  const type = selectedOutboundType();

  try {
    const payload = parseWsPayload();
    let messageId: string;

    if (type === "client.reference_bundle") {
      messageId = orchestratorWs.sendReferenceBundle(payload as ClientReferenceBundlePayload);
    } else if (type === "client.fp_zones_update") {
      const fpPayload = payload as {
        heatmap_width: number;
        heatmap_height: number;
        fp_zones: FpZoneNorm[];
      };
      messageId = orchestratorWs.sendFpZonesUpdate(fpPayload);
    } else {
      messageId = orchestratorWs.sendActiveReferenceView({
        view_index: readActiveViewIndex(payload),
      });
    }

    setText(wsLastSent, shortMessageId(messageId));
  } catch (error) {
    const message = error instanceof Error ? error.message : "send failed";
    setText(wsLastSent, message);
  }
}

function parseWsPayload() {
  const raw = wsPayload?.value.trim() ?? "";

  if (!raw) {
    throw new Error("payload empty");
  }

  return JSON.parse(raw) as unknown;
}

function readActiveViewIndex(payload: unknown) {
  const formValue = Number(wsActiveView?.value ?? 0);

  if (payload && typeof payload === "object" && "view_index" in payload) {
    const payloadValue = Number((payload as { view_index: unknown }).view_index);
    if (Number.isInteger(payloadValue) && payloadValue >= 0 && payloadValue <= 4) {
      return payloadValue;
    }
  }

  if (!Number.isInteger(formValue) || formValue < 0 || formValue > 4) {
    throw new Error("view_index must be 0..4");
  }

  return formValue;
}

function fillWsPayloadExample() {
  const type = selectedOutboundType();

  if (wsPayload) {
    wsPayload.value = JSON.stringify(createExamplePayload(type), null, 2);
  }

  if (wsActiveView) {
    wsActiveView.disabled = type !== "client.set_active_reference_view";
  }
}

function selectedOutboundType(): OutboundWsType {
  const value = wsMessageType?.value;

  if (
    value === "client.reference_bundle" ||
    value === "client.fp_zones_update" ||
    value === "client.set_active_reference_view"
  ) {
    return value;
  }

  return "client.reference_bundle";
}

function createExamplePayload(type: OutboundWsType) {
  if (type === "client.fp_zones_update") {
    return {
      heatmap_width: 640,
      heatmap_height: 480,
      fp_zones: exampleFpZones(),
    };
  }

  if (type === "client.set_active_reference_view") {
    return {
      view_index: Number(wsActiveView?.value ?? 0),
    };
  }

  return createReferenceBundleExample();
}

function createReferenceBundleExample(): ClientReferenceBundlePayload {
  return {
    product_type: "default",
    joint_view_index: 0,
    heatmap_width: 640,
    heatmap_height: 480,
    views: [0, 1, 2, 3, 4].map((cameraId) => ({
      frame: {
        camera_id: cameraId,
        frame_id: "0",
        shm_name: `reference_camera_${cameraId}`,
        width: 640,
        height: 480,
        stride: 1920,
        shm_offset: 0,
        pixel_format: "bgr_u8",
        channels: 3,
      },
      interest_roi: {
        x: 0,
        y: 0,
        width: 640,
        height: 480,
      },
      joint_roi:
        cameraId === 0
          ? {
              x: 120,
              y: 90,
              width: 360,
              height: 260,
            }
          : null,
    })) as ClientReferenceBundlePayload["views"],
    fp_zones: exampleFpZones(),
  };
}

function exampleFpZones(): FpZoneNorm[] {
  return [
    {
      id: "fp-1",
      note: "ignore area",
      points_norm_heatmap: [
        { x: 0.15, y: 0.15 },
        { x: 0.35, y: 0.15 },
        { x: 0.35, y: 0.32 },
        { x: 0.15, y: 0.32 },
      ],
    },
  ];
}

function updateWsSendButton() {
  if (wsSend) {
    wsSend.disabled = !orchestratorWs.isOpen;
  }
}

function shortMessageId(messageId: string) {
  return messageId.length > 12 ? `${messageId.slice(0, 8)}...` : messageId;
}
