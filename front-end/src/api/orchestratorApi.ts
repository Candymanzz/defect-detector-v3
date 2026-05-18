import { appEnv } from "../config/env";
import { HttpClient } from "./httpClient";
import type {
  GeometryLatestSnapshot,
  GeometryRuntimeConfig,
  LightBrightnessSettings,
  LightBrightnessUpdateRequest,
  LightBrightnessUpdateResponse,
  UiCameraList,
  UiLatestSnapshot,
} from "./types";

const http = new HttpClient(appEnv.apiRequestBaseUrl);
const LIGHT_BRIGHTNESS_PATH = "/api/orchestrator/light/brightness";

export const orchestratorApi = {
  url: (path: string) => http.url(path),

  lightBrightnessPath: LIGHT_BRIGHTNESS_PATH,

  async health() {
    return http.text("/health", {
      headers: {
        Accept: "text/plain",
      },
      timeoutMs: 3000,
    });
  },

  async listCameras() {
    return http.json<UiCameraList>("/api/cameras");
  },

  async getLatestSnapshot(cameraId: number) {
    return http.json<UiLatestSnapshot>(`/api/camera/${cameraId}/latest.json`);
  },

  currentFrameUrl(cameraId: number) {
    return http.url(`/api/camera/${cameraId}/current.jpg`);
  },

  heatmapUrl(cameraId: number) {
    return http.url(`/api/camera/${cameraId}/heatmap.u8`);
  },

  heatmapArtifactUrl(artifactId: string) {
    return http.url(`/api/heatmap-artifact/${artifactId}`);
  },

  async getHeatmap(cameraId: number) {
    return http.arrayBuffer(`/api/camera/${cameraId}/heatmap.u8`, {
      headers: {
        Accept: "application/octet-stream",
      },
    });
  },

  async getHeatmapArtifact(artifactId: string) {
    return http.arrayBuffer(`/api/heatmap-artifact/${artifactId}`, {
      headers: {
        Accept: "application/octet-stream",
      },
    });
  },

  async listGeometryCameras() {
    return http.json<UiCameraList>("/api/geometry/cameras");
  },

  async getGeometryLatestSnapshot(cameraId: number) {
    return http.json<GeometryLatestSnapshot>(`/api/geometry/camera/${cameraId}/latest.json`);
  },

  async getGeometryRuntime() {
    return http.json<GeometryRuntimeConfig>("/api/client/geometry-runtime");
  },

  async replaceGeometryRuntime(overrides: Record<string, unknown>) {
    return http.json<{ ok: true }>("/api/client/geometry-runtime", {
      method: "PUT",
      body: overrides,
    });
  },

  async clearGeometryRuntime() {
    return http.json<{ ok: true }>("/api/client/geometry-runtime", {
      method: "DELETE",
    });
  },

  async getLightBrightness() {
    return http.json<LightBrightnessSettings>(LIGHT_BRIGHTNESS_PATH);
  },

  async setLightBrightness(brightnessPercent: number) {
    const body: LightBrightnessUpdateRequest = {
      brightness_percent: brightnessPercent,
    };

    return http.json<LightBrightnessUpdateResponse>(LIGHT_BRIGHTNESS_PATH, {
      method: "PUT",
      body,
    });
  },

  async clientProxyJson<T>(path: string, options = {}) {
    return http.json<T>(clientProxyPath(path), options);
  },
};

function clientProxyPath(path: string) {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  return normalized.startsWith("/api/client/") ? normalized : `/api/client${normalized}`;
}
