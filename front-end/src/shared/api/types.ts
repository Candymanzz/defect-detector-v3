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

export type InspectionStateResponse = {
  ok: true;
  requestedCameraIds: CameraId[];
  changedCameraIds: CameraId[];
  cancelledCameraIds: CameraId[];
  enabledCameraIds: CameraId[];
  disabledCameraIds: CameraId[];
  unknownCameraIds: CameraId[];
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
  overall_pass?: boolean | null;
  action?: string | null;
  anomaly_score?: number | null;
  python_status?: string | null;
  geometry_status?: string | null;
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

export type LightEndpointBrightness = {
  id: string;
  brightness_percent: number;
  mv_le_brightness: number;
};

export type LightBrightnessPercent = number | string;

export type LightBrightnessSettings = {
  default_brightness_percent: number;
  endpoints: LightEndpointBrightness[];
  upstream_base_url?: string;
  brightness_percent?: number;
  com_controller_percent?: number;
  mv_le_brightness?: number;
  scale?: string;
};

export type LightEndpointBrightnessUpdate = {
  id: string;
  brightness_percent?: LightBrightnessPercent;
  brightness?: LightBrightnessPercent;
};

export type LightBrightnessUpdateRequest = {
  brightness_percent?: LightBrightnessPercent;
  default_brightness_percent?: LightBrightnessPercent;
  brightness?: LightBrightnessPercent | number[];
  value?: LightBrightnessPercent;
  endpoints?: Record<string, LightBrightnessPercent> | LightEndpointBrightnessUpdate[];
};

export type LightBrightnessUpdateResponse = {
  ok: true;
  default_brightness_percent: number;
  endpoints: LightEndpointBrightness[];
  brightness_percent?: number;
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

export type AnalysisSettings = {
  default_threshold: number;
  use_patchcore: boolean;
  min_defect_area: number;
  min_scratch_aspect: number;
  min_diff_signal: number;
  diff_percentile: number;
  scratch_score_floor: number;
  scratch_aspect_floor: number;
  edge_suppress_factor: number;
  text_min_contrast: number;
  text_structure_threshold: number;
  contrast_loss_boost: number;
  contrast_loss_ref_grad: number;
  contrast_loss_cur_grad: number;
  enable_clahe: boolean;
  clahe_clip_limit: number;
  fp_recheck_enabled: boolean;
  fp_trigger_diff_q90: number;
};

export type AnalysisSettingsResponse = {
  analysis_profile?: string;
  product_type?: string;
  settings: AnalysisSettings;
  defaults: AnalysisSettings;
  overrides: Partial<AnalysisSettings>;
};

export type AnalysisSettingsUpdateRequest = Partial<AnalysisSettings>;
