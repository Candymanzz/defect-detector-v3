import { appEnv } from "../config/env";
import { HttpClient } from "./httpClient";
import type {
  AnalysisSettingsResponse,
  AnalysisSettingsUpdateRequest,
  AnalysisPresetResponse,
  SimpleAnalysisKnobs,
  ProAnalysisKnobs,
  ClientModeResponse,
  TestAnalyzeResponse,
  AcceptLearnedNormalsRequest,
  AcceptLearnedNormalsResponse,
  CameraRuntimeSettings,
  CameraRuntimeSettingsUpdate,
  FpZonesResponse,
  GeometryLatestSnapshot,
  GeometryRuntimeConfig,
  InspectionStateResponse,
  InspectionResetResponse,
  InspectionLayout,
  LightBrightnessSettings,
  LightBrightnessUpdateRequest,
  LightBrightnessUpdateResponse,
  LightModeSettings,
  LearnedNormalCase,
  LineDirection,
  LineDirectionSettings,
  LineDirectionUpdateResponse,
  FrameArchiveHistoryResponse,
  FrameArchiveSettings,
  FrameArchiveSettingsUpdateResponse,
  PlcSignalsResponse,
  PlcStatusResponse,
  PlcTimeoutsResponse,
  PlcWriteSignalRequest,
  UiCameraList,
  UiLatestSnapshot,
} from "./types";

const http = new HttpClient(appEnv.apiRequestBaseUrl);
const LIGHT_BRIGHTNESS_PATH = "/api/orchestrator/light/brightness";
const LIGHT_MODE_PATH = "/api/orchestrator/light/mode";
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

  async getInspectionLayout() {
    return http.json<InspectionLayout>("/api/inspection-layout");
  },

  async getLatestSnapshot(cameraId: number) {
    return http.json<UiLatestSnapshot>(`/api/camera/${cameraId}/latest.json`);
  },

  async getCameraSettings(cameraId: number) {
    return http.json<CameraRuntimeSettings>(`/api/camera/${cameraId}/settings`);
  },

  async updateCameraSettings(cameraId: number, update: CameraRuntimeSettingsUpdate) {
    return http.json<CameraRuntimeSettings>(`/api/camera/${cameraId}/settings`, {
      method: "PATCH",
      body: update,
    });
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

  async getGeometryRuntime(cameraId: number | null = null) {
    return http.json<GeometryRuntimeConfig>(geometryRuntimePath(cameraId));
  },

  async replaceGeometryRuntime(overrides: Record<string, unknown>, cameraId: number | null = null) {
    return http.json<{ ok: true }>(geometryRuntimePath(cameraId), {
      method: "PUT",
      body: overrides,
    });
  },

  async patchGeometryRuntime(overrides: Record<string, unknown>, cameraId: number | null = null) {
    return http.json<{ ok: true }>(geometryRuntimePath(cameraId), {
      method: "PATCH",
      body: overrides,
    });
  },

  async clearGeometryRuntime(cameraId: number | null = null) {
    return http.json<{ ok: true }>(geometryRuntimePath(cameraId), {
      method: "DELETE",
    });
  },

  async getInspectionStatus() {
    return http.json<InspectionStateResponse>("/api/client/inspection/status");
  },

  async setInspectionEnabled(cameraId: number, enabled: boolean) {
    const action = enabled ? "start" : "stop";
    return http.json<InspectionStateResponse>(`/api/client/inspection/${action}`, {
      method: "POST",
      body: {
        cameraId,
      },
    });
  },

  async resetInspection() {
    return http.json<InspectionResetResponse>("/api/client/inspection/clear-reference", {
      method: "POST",
      body: {},
    });
  },

  async stopAllInspections() {
    return http.json<InspectionStateResponse>("/api/client/inspection/stop-all", {
      method: "POST",
      body: {},
    });
  },

  async startAllInspections() {
    return http.json<InspectionStateResponse>("/api/client/inspection/start-all", {
      method: "POST",
      body: {},
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

  async getLightMode() {
    return http.json<LightModeSettings>(LIGHT_MODE_PATH);
  },

  async setLightMode(constant: boolean) {
    return http.json<LightModeSettings>(LIGHT_MODE_PATH, {
      method: "PUT",
      body: { constant },
    });
  },

  async getLineDirection() {
    return http.json<LineDirectionSettings>("/api/client/line-direction");
  },

  async setLineDirection(direction: LineDirection) {
    return http.json<LineDirectionUpdateResponse>("/api/client/line-direction", {
      method: "PUT",
      body: { direction },
    });
  },

  async getPlcStatus() {
    return http.json<PlcStatusResponse>("/api/client/plc/status");
  },

  async getPlcSignals() {
    return http.json<PlcSignalsResponse>("/api/client/plc/signals");
  },

  async writePlcSignal(request: PlcWriteSignalRequest) {
    return http.json<PlcSignalsResponse>("/api/client/plc/signals", {
      method: "POST",
      body: request,
    });
  },

  async getPlcTimeouts() {
    return http.json<PlcTimeoutsResponse>("/api/client/plc/timeouts");
  },

  async putPlcTimeouts(timeouts: Record<string, number>) {
    return http.json<PlcTimeoutsResponse>("/api/client/plc/timeouts", {
      method: "PUT",
      body: { timeouts },
    });
  },

  async getFrameArchiveSettings() {
    return http.json<FrameArchiveSettings>("/api/client/frame-archive");
  },

  async setFrameArchiveMaxFrames(maxFramesPerCamera: number) {
    return http.json<FrameArchiveSettingsUpdateResponse>("/api/client/frame-archive", {
      method: "PUT",
      body: { max_frames_per_camera: maxFramesPerCamera },
    });
  },

  async getFrameArchiveHistory(cameraId: number, phaseId?: number, groupId?: number) {
    const params = new URLSearchParams();
    if (phaseId != null) params.set("phase_id", String(phaseId));
    if (groupId != null) params.set("group_id", String(groupId));
    const query = params.size > 0 ? `?${params.toString()}` : "";
    return http.json<FrameArchiveHistoryResponse>(`/api/frame-archive/cameras/${cameraId}/history${query}`);
  },

  async deleteFrameArchiveFrame(cameraId: number, frameId: string | number) {
    return http.json<{ ok: true; deleted: number }>(
      `/api/frame-archive/cameras/${cameraId}/frames/${encodeURIComponent(String(frameId))}`,
      { method: "DELETE" },
    );
  },

  async clearFrameArchive(cameraIds?: number[]) {
    return http.json<{ ok: true; deleted: number }>("/api/frame-archive", {
      method: "DELETE",
      body: cameraIds && cameraIds.length > 0 ? { cameraIds } : {},
    });
  },

  async getJson<T>(path: string) {
    return http.json<T>(path);
  },

  async getAnalysisSettings(productType: string) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}`);
  },

  async getCameraAnalysisSettings(cameraId: number) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}`);
  },

  async getDefaultAnalysisSettings() {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/defaults`);
  },

  async getFpZones(productType: string) {
    return http.json<FpZonesResponse>(
      `/api/orchestrator/fp-zones/${encodeURIComponent(productType)}`,
    );
  },

  async setAnalysisSettings(productType: string, update: AnalysisSettingsUpdateRequest) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}`, {
      method: "PUT",
      body: update,
    });
  },

  async setCameraAnalysisSettings(cameraId: number, update: AnalysisSettingsUpdateRequest) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}`, {
      method: "PUT",
      body: update,
    });
  },

  async getSimpleAnalysisSettings(productType: string) {
    return http.json<AnalysisPresetResponse<SimpleAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}/simple`,
    );
  },

  async setSimpleAnalysisSettings(productType: string, knobs: SimpleAnalysisKnobs) {
    return http.json<AnalysisPresetResponse<SimpleAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}/simple`,
      { method: "PUT", body: knobs },
    );
  },

  async getCameraSimpleAnalysisSettings(cameraId: number) {
    return http.json<AnalysisPresetResponse<SimpleAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}/simple`,
    );
  },

  async setCameraSimpleAnalysisSettings(cameraId: number, knobs: SimpleAnalysisKnobs) {
    return http.json<AnalysisPresetResponse<SimpleAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}/simple`,
      { method: "PUT", body: knobs },
    );
  },

  async getProAnalysisSettings(productType: string) {
    return http.json<AnalysisPresetResponse<ProAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}/pro`,
    );
  },

  async setProAnalysisSettings(productType: string, knobs: ProAnalysisKnobs) {
    return http.json<AnalysisPresetResponse<ProAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}/pro`,
      { method: "PUT", body: knobs },
    );
  },

  async getCameraProAnalysisSettings(cameraId: number) {
    return http.json<AnalysisPresetResponse<ProAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}/pro`,
    );
  },

  async setCameraProAnalysisSettings(cameraId: number, knobs: ProAnalysisKnobs) {
    return http.json<AnalysisPresetResponse<ProAnalysisKnobs>>(
      `${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}/pro`,
      { method: "PUT", body: knobs },
    );
  },

  async getClientMode() {
    return http.json<ClientModeResponse>("/api/client/mode");
  },

  async setTestMode(enabled: boolean) {
    return http.json<ClientModeResponse>("/api/client/mode/test", {
      method: "POST",
      body: { enabled },
    });
  },

  async testAnalyzeArchiveFrame(cameraId: number, frameId: string) {
    return http.json<TestAnalyzeResponse>("/api/client/inspection/test-analyze", {
      method: "POST",
      body: { cameraId, frameId, source: "archive" },
    });
  },

  async acceptLearnedNormals(request: AcceptLearnedNormalsRequest) {
    return http.json<AcceptLearnedNormalsResponse>("/api/client/learning/accept-all-as-normal", {
      method: "POST",
      body: {
        frameId: request.frameId,
        productType: request.productType,
        cameraId: request.cameraId,
        note: request.note ?? "",
      },
    });
  },

  async getLearningReviews(productType: string, cameraId?: number) {
    const query = new URLSearchParams({ product_type: productType });
    if (cameraId !== undefined) {
      query.set("cameraId", String(cameraId));
    }
    return http.json<{ reviews: unknown[] }>(`/api/client/learning/reviews?${query}`);
  },

  async getLearnedNormals(productType: string, cameraId: number) {
    const query = new URLSearchParams({ productType, cameraId: String(cameraId) });
    return http.json<{ cases: LearnedNormalCase[] }>(`/api/client/learning/accepted-cases?${query}`);
  },

  learnedNormalImageUrl(caseId: string) {
    return http.url(`/api/client/learning/accepted-cases/${encodeURIComponent(caseId)}/image`);
  },

  async deleteLearnedNormal(caseId: string) {
    return http.json<{ deleted: boolean; case_id?: string }>(
      `/api/client/learning/accepted-cases/${encodeURIComponent(caseId)}`,
      { method: "DELETE" },
    );
  },

  async clearLearnedNormals() {
    return http.json<{ deleted: boolean; cases_count?: number }>("/api/client/learning/accepted-cases", {
      method: "DELETE",
    });
  },

  async resetAnalysisSettings(productType: string) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/${encodeURIComponent(productType)}`, {
      method: "DELETE",
    });
  },

  async resetCameraAnalysisSettings(cameraId: number) {
    return http.json<AnalysisSettingsResponse>(`${ANALYSIS_SETTINGS_PATH}/camera/${cameraId}`, {
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

function geometryRuntimePath(cameraId: number | null) {
  const path = "/api/client/geometry-runtime";
  return cameraId === null ? path : `${path}?cameraId=${encodeURIComponent(String(cameraId))}`;
}

function versionImageUrl(imageUrl: string, version?: string | number) {
  if (version === undefined || version === null || version === "") {
    return imageUrl;
  }

  const separator = imageUrl.includes("?") ? "&" : "?";
  return `${imageUrl}${separator}frame_ts=${encodeURIComponent(String(version))}`;
}
