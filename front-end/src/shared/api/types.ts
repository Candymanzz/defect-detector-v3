export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonObject | JsonValue[];
export type JsonObject = {
  [key: string]: JsonValue;
};

export type ApiErrorResponse = {
  error: string;
};

export type ApiOk = {
  ok: true;
};

export type CameraId = number;
export type EpochMs = number;
export type FrameId = number;
export type RelativeApiPath = `/${string}`;

export type UiCameraList = {
  cameras: CameraId[];
};

export type UiCaptureSize = {
  width: number;
  height: number;
};

export type UiImageArtifact = UiCaptureSize & {
  path: RelativeApiPath;
};

export type UiLatestSnapshot = {
  cameraId: CameraId;
  frameId: FrameId;
  productType: string;
  detectorId: string;
  shmName: string;
  updatedAtMs: EpochMs;
  hasCurrent: boolean;
  hasHeatmap: boolean;
  capture: UiCaptureSize;
  currentJpeg: UiImageArtifact;
  heatmapU8: UiImageArtifact;
};

export type GeometryLatestSnapshot = {
  cameraId: CameraId;
  frameId: FrameId;
  updatedAtMs: EpochMs;
  latestJsonPath: RelativeApiPath;
  geometry: GeometryInspectResponse;
};

export type GeometryInspectResponse = JsonObject & {
  status?: string;
  overallPass?: boolean;
  homographyRefToCurrent?: number[][];
  metrics?: JsonObject;
};

export type GeometryRuntimeConfig = {
  runtimeOverrides: GeometryRuntimeOverrides;
  effectiveForNextGeometryInspect: GeometryRuntimeEffectiveConfig;
};

export type GeometryRuntimeOverrides = Partial<{
  mainRoi: GeometryRuntimeRoi;
  main_roi: GeometryRuntimeRoi;
  maxShiftMm: number;
  max_shift_mm: number;
  maxRotationDeg: number;
  max_rotation_deg: number;
  maxWrinklesScore: number;
  max_wrinkles_score: number;
}> &
  JsonObject;

export type GeometryRuntimeEffectiveConfig = JsonObject;

export type GeometryRuntimeRoi = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type LightBrightnessSettings = {
  brightness_percent: number;
  com_controller_percent: number;
  mv_le_brightness: number;
  scale: string;
};

export type LightBrightnessUpdateResponse = {
  ok: true;
  brightness_percent: number;
};

export type LightBrightnessUpdateRequest = {
  brightness_percent: number;
};

export type StubHealth = {
  status: "ok" | string;
  service: string;
};

export type FanOutEvent = {
  cameraId: CameraId;
  frameId: FrameId;
  overallPass: boolean;
  action: string;
  anomalyScore: number;
  pythonStatus: string;
  geometryStatus: string;
  timestampMs: EpochMs;
};

export type StubMetrics = {
  queue_depth?: number;
  queue_dropped_total?: number;
  queue_pushed_total?: number;
  artificial_delay_ms?: number;
};

export type FpZonePointNorm = {
  x: number;
  y: number;
};

export type FpZone = {
  id?: string;
  note?: string;
  points_norm_heatmap: FpZonePointNorm[];
};

export type FpZonesResponse = {
  fp_zones?: FpZone[];
  [key: string]: JsonValue | FpZone[] | undefined;
};

export type FpZonesUpdateRequest = {
  fp_zones: FpZone[];
};
