import { appEnv } from "../config/env";
import { HttpClient } from "./httpClient";
import type {
  AnalysisSettingsResponse,
  AnalysisSettingsUpdateRequest,
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
const ANALYSIS_SETTINGS_PATH = "/api/orchestrator/analysis-settings";

export const orchestratorApi = {
  url: (path: string) => http.url(path),
  imageUrl: (path: string, version?: string | number) => versionImageUrl(http.url(path), version),

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

  currentFrameUrl(cameraId: number, version?: string | number) {
    return versionImageUrl(http.url(`/api/camera/${cameraId}/current.jpg`), version);
  },

  heatmapUrl(cameraId: number) {
    return http.url(`/api/camera/${cameraId}/heatmap.u8`);
  },

  heatmapArtifactUrl(artifactId: string) {
    return http.url(`/api/heatmap-artifact/${artifactId}`);
  },

  streamMjpegUrl(cameraId: number) {
    return http.url(`/api/camera/${cameraId}/stream.mjpeg`);
  },

  async getHeatmap(cameraId: number) {
    return http.arrayBuffer(`/api/camera/${cameraId}/heatmap.u8`, {
      headers: {
        Accept: "application/octet-stream",
      },
    });
  },

  async getHeatmapArtifact(artifactId: string, signal?: AbortSignal) {
    return http.arrayBuffer(`/api/heatmap-artifact/${artifactId}`, {
      headers: {
        Accept: "application/octet-stream",
      },
      signal,
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

  async setLightBrightness(update: number | LightBrightnessUpdateRequest) {
    const body: LightBrightnessUpdateRequest =
      typeof update === "number"
        ? {
            brightness_percent: update,
          }
        : update;

    return http.json<LightBrightnessUpdateResponse>(LIGHT_BRIGHTNESS_PATH, {
      method: "PUT",
      body,
    });
  },

  async getAnalysisSettings(productType: string) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}`);
  },

  async getDefaultAnalysisSettings() {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/defaults`);
  },

  async setAnalysisSettings(productType: string, update: AnalysisSettingsUpdateRequest) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}`, {
      method: "PUT",
      body: update,
    });
  },

  async resetAnalysisSettings(productType: string) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}`, {
      method: "DELETE",
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

function versionImageUrl(imageUrl: string, version?: string | number) {
  if (version === undefined || version === null || version === "") {
    return imageUrl;
  }

  const separator = imageUrl.includes("?") ? "&" : "?";
  return `${imageUrl}${separator}frame_ts=${encodeURIComponent(String(version))}`;
}
