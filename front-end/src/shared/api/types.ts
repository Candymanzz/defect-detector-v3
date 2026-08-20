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

export type InspectionResetResponse = {
  ok: true;
  cleared: boolean;
  session_state: string;
  inspection_enabled?: boolean;
  signals_editable?: boolean;
  timeouts_editable?: boolean;
};

export type CameraId = number;
export type EpochMs = number;
export type FrameId = number;
export type RelativeApiPath = `/${string}`;

export type UiCameraList = {
  cameras: CameraId[];
};

export type CameraTriggerMode = "continuous" | "software" | "line0" | "line1";

export type CameraRuntimeSettings = {
  status?: string;
  camera_id: CameraId;
  exposure_us: number;
  gain_db: number;
  gamma: number;
  black_level: number;
  capture_trigger_mode: CameraTriggerMode | string;
  effective_trigger_mode?: CameraTriggerMode | string;
  pixel_format?: string;
  width?: number;
  height?: number;
  frame_timeout_ms: number;
  streaming?: boolean;
  mvs_available?: boolean;
  ok?: true;
};

export type CameraRuntimeSettingsUpdate = Partial<
  Pick<
    CameraRuntimeSettings,
    "exposure_us" | "gain_db" | "gamma" | "black_level" | "capture_trigger_mode" | "frame_timeout_ms"
  >
>;

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

export type GeometryInspectResponse = {
  status?: string;
  overallPass?: boolean;
  alignmentPass?: boolean;
  concentricityPass?: boolean;
  jointPass?: boolean;
  wrinklesPass?: boolean;
  shiftXmm?: number;
  shiftYmm?: number;
  rotationDeg?: number;
  concentricityMm?: number;
  /** Radial deviation from etalon: hypot(shiftXmm, shiftYmm). */
  deviationRadiusMm?: number;
  maxShiftMm?: number;
  maxRotationDeg?: number;
  maxConcentricityMm?: number;
  pixelsToMm?: number;
  jointDefectMm?: number;
  jointParallelismDeg?: number;
  jointWidthMm?: number;
  jointWidthTopMm?: number;
  jointWidthBottomMm?: number;
  jointTaperMm?: number;
  jointVisibility?: number;
  wrinklesScore?: number;
  homographyRefToCurrent?: number[] | number[][];
  debugImageBase64?: string;
  metrics?: JsonObject;
} & JsonObject;

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
  jointSeamSegmentationEnabled: boolean;
  joint_seam_segmentation_enabled: boolean;
  jointSeamSegmentationSensitivity: number;
  joint_seam_segmentation_sensitivity: number;
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
  camera_ids?: CameraId[];
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
  ok: boolean;
  hardware_applied?: boolean;
  hardware_errors?: string[];
  default_brightness_percent: number;
  endpoints: LightEndpointBrightness[];
  brightness_percent?: number;
};

export type LightModeSettings = {
  constant: boolean;
  mode: "interval" | "constant";
};

export type LineDirection = "forward" | "reverse";

export type LineDirectionSettings = {
  direction: LineDirection;
  source: "manual";
};

export type LineDirectionUpdateRequest = {
  direction: LineDirection;
};

export type LineDirectionUpdateResponse = {
  ok: true;
  direction: LineDirection;
  source: "manual";
};

export type FrameArchiveSettings = {
  enabled: boolean;
  directory: string;
  max_frames_per_camera: number;
  max_allowed_frames_per_camera: number;
};

export type FrameArchiveSettingsUpdateResponse = {
  ok: true;
  max_frames_per_camera: number;
};

export type FrameArchiveHistoryFrame = {
  frame_id: string;
  inspection_id: string;
  overall_pass: boolean;
  action: string;
  anomaly_score: number;
  python_status: string;
  geometry_status: string;
  product_type: string;
  detector_id: string;
  saved_at_ms: number;
  has_heatmap: boolean;
  frame_url: string;
  heatmap_url?: string;
  heatmap_width?: number;
  heatmap_height?: number;
  result_url: string;
};

export type FrameArchiveHistoryResponse = {
  camera_id: number;
  max_frames_per_camera: number;
  frames: FrameArchiveHistoryFrame[];
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
  product_type: string;
  zones: FpZone[];
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

export type SimpleAnalysisKnobs = {
  threshold: number;
  sensitivity: number;
};

export type ProAnalysisKnobs = {
  threshold: number;
  noise_tolerance: number;
  scratch_sensitivity: number;
  edge_suppression: number;
  text_handling: number;
  preprocess_strength: number;
};

export type ClientModeResponse = {
  ok: boolean;
  session_state: string;
  test_mode: boolean;
  message?: string;
};

export type TestAnalyzeResponse = {
  ok: boolean;
  jobId: string;
  cameraId: number;
  frameId: number;
};

export type AcceptLearnedNormalsRequest = {
  frameId: string | number;
  productType: string;
  cameraId?: number;
  note?: string;
};

export type AcceptLearnedNormalsResponse = {
  saved?: boolean;
  accepted_count?: number;
  inspection_id?: string;
  learned_review_id?: string;
  accepted_case_ids?: string[];
  accepted_cases?: LearnedNormalCase[];
  affects_original_pipeline_decision?: boolean;
  [key: string]: unknown;
};

export type LearnedNormalCase = {
  id: string;
  product_type: string;
  polygon_norm?: Array<{ x: number; y: number }>;
  source_inspection_id?: string;
  source_defect_id?: string;
  created_at?: string;
  note?: string;
  enabled?: boolean;
};

export type AnalysisPresetResponse<TKnobs> = AnalysisSettingsResponse & {
  knobs: TKnobs | null;
  detector_id?: string;
};

export type PlcSignalState = {
  name: string;
  description?: string;
  area: string;
  address: string;
  bucketGroupId?: number | null;
  lastValue?: boolean | null;
  direction?: string;
  writable?: boolean;
};

export type PlcTimeoutDefinition = {
  name: string;
  description?: string;
  address: string;
  encoding?: string;
  unit?: string;
};

export type PlcTimeoutState = {
  name: string;
  description?: string;
  address: string;
  valueUnits: number;
  valueMs: number;
  rawWord: number;
  encoding: string;
  unit: string;
};

export type PlcStatusResponse = {
  ok: true;
  enabled: boolean;
  inspection_in_flight: boolean;
  inspection_enabled: boolean;
  editable: boolean;
  timeouts_editable?: boolean;
  signals_editable?: boolean;
  timeout_definitions?: PlcTimeoutDefinition[];
  signals: PlcSignalState[];
};

export type PlcSignalsResponse = {
  ok: true;
  enabled: boolean;
  inspection_in_flight: boolean;
  inspection_enabled: boolean;
  editable: boolean;
  signals: PlcSignalState[];
};

export type PlcWriteSignalRequest = {
  signal: string;
  value: boolean;
  pulse?: boolean;
};

export type PlcTimeoutsResponse = {
  ok: true;
  enabled: boolean;
  inspection_in_flight: boolean;
  inspection_enabled: boolean;
  editable: boolean;
  timeouts_editable?: boolean;
  unit?: string;
  timeouts: PlcTimeoutState[];
  signals?: PlcSignalState[];
};
