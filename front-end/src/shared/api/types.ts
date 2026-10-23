import type { DetectorResult } from "../ws/types";

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
  jointRimSkewDeg?: number;
  jointGapLeftMm?: number;
  jointGapRightMm?: number;
  jointGapAsymmetryMm?: number;
  rimSkewPass?: boolean;
  maxJointRimSkewDeg?: number;
  maxJointGapAsymmetryMm?: number;
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
  anomaly_score: number | null;
  python_status: string;
  geometry_status: string;
  product_type: string;
  detector_id: string;
  saved_at_ms: number;
  has_heatmap: boolean;
  frame_url: string;
  frame_width?: number;
  frame_height?: number;
  heatmap_url?: string;
  heatmap_width?: number;
  heatmap_height?: number;
  result_url: string;
  learned_review_id?: string;
  geometry?: GeometryInspectResponse;
  detector_result?: DetectorResult;
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
  /** false — не гасить отклик на стабильных краях детали. */
  enable_edge_suppression: boolean;
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
  /** Threshold is a unit interval; detailed strengths below are percentages. */
  threshold: number;
  /** Detailed strength values use the backend's 0–100 scale. */
  noise_tolerance: number;
  scratch_sensitivity: number;
  edge_suppression: number;
  text_handling: number;
  preprocess_strength: number;
  /** Усиление чувствительности на дальнем краю изделия (линия перспективы), 50 = стандарт. */
  far_edge_boost: number;
};

/** Силы групп алгоритма (0–100), без threshold/sensitivity — см. ANALYSIS_SETTINGS_INTEGRATION.md */
export type StrengthKnobs = {
  noise_tolerance: number;
  scratch_sensitivity: number;
  edge_suppression: number;
  text_handling: number;
  preprocess_strength: number;
  far_edge_boost: number;
};

export type StrengthKnobsResponse = {
  analysis_profile: string;
  saved: boolean;
  strengths: StrengthKnobs;
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
  pinId: string;
  pinJpegSha256: string;
};

export type PinTestFrameRequest = {
  cameraId: number;
  frameId: string | number;
  source?: "archive" | "artifact";
  httpPath?: string;
};

export type PinTestFrameResponse = {
  ok: boolean;
  cameraId: number;
  frameId: number;
  pinId: string;
  jpegSha256: string;
  imageHttpPath: string;
};

export type AcceptLearnedNormalsRequest = {
  frameId: string | number;
  productType: string;
  cameraId?: number;
  learnedReviewId?: string;
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
  bbox?: { x: number; y: number; width: number; height: number };
  polygon?: Array<{ x: number; y: number }>;
  coordinate_space?: { width: number; height: number };
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

/** Контроль стыка этикетки: линия p0 -> p1 в долях кадра (0..1), допуски в px исходного кадра. */
export type SeamSettings = {
  enabled: boolean;
  p0: [number, number];
  p1: [number, number];
  /** Полоса поиска стыка вокруг линии, ± px. */
  band: number;
  /** Допуск отклонения линии стыка в середине, px. */
  tol_mid: number;
  /** Допуск на концах стыка, px. */
  tol_edge: number;
  /** Доля длины стыка с каждого конца, считающаяся краем (0..0.5). */
  edge_frac: number;
};

export type DetectorSettings = {
  /** 0..1, выше — строже (мельче отличия считаются дефектом). */
  sensitivity: number;
  /** (0..1], брак при anomaly_score >= threshold (score = доля аномальной площади ROI / 5%). */
  threshold: number;
  /** Допуск на микросдвиг кадра, px исходного кадра. */
  shift_tolerance: number;
  /** Минимальная площадь дефекта, px исходного кадра. */
  min_blob_area: number;
  /** (0..1], во сколько раз кадр уменьшается перед анализом. */
  inspect_scale: number;
  /** Контроль стыка этикетки (настраивается для конкретной камеры). */
  seam: SeamSettings;
};

export type DetectorSettingsResponse = {
  analysis_profile?: string;
  settings: DetectorSettings;
  defaults: DetectorSettings;
  saved?: boolean;
};

export type DetectorSettingsUpdate = Partial<DetectorSettings>;

/**
 * Геометрия линий этикетки (cppAnalisSurface): две независимые зоны камеры.
 * «Стык» — тонкая светлая линия (ridge), «Кромка» — граница светлое/тёмное (step).
 */
export type LineZoneSettings = {
  enabled: boolean;
  mode: "ridge" | "step";
  /** Контрольные точки линии в долях кадра (0..1); через них проходит сплайн. */
  pts: Array<[number, number]>;
  /** Номера участков (между pts[i] и pts[i+1]), исключённых из проверки. */
  skip: number[];
  /** Только step: 0 — любая граница, 1 — светлее по +нормали, -1 — темнее. */
  polarity: -1 | 0 | 1;
  fit_deg: number;
  /** Только step: длина участка (px), по которому строится локальная опорная линия. */
  smooth_win: number;
  /** Полоса поиска вокруг линии, ± px. */
  band: number;
  tol_mid: number;
  tol_edge: number;
  edge_frac: number;
  min_contrast: number;
  /** Доля линии, которая может быть подряд «не найдена» (1 — не проверять). */
  max_gap: number;
};

export type LinesSettings = {
  seam: LineZoneSettings;
  edge: LineZoneSettings;
};

export type LinesSettingsResponse = {
  analysis_profile?: string;
  lines: LinesSettings;
  defaults: LinesSettings;
};

export type LinesSettingsUpdate = {
  seam?: Partial<LineZoneSettings>;
  edge?: Partial<LineZoneSettings>;
};

/** Результат одной зоны на последнем кадре: вердикт и сжатые массивы для диаграммы (до 400 отсчётов). */
export type LineZoneResult = {
  found: boolean;
  reject: boolean;
  gap_reject: boolean;
  /** max |отклонение| / допуск; > 1 — брак. */
  ratio: number;
  max_dev: number;
  worst_t: number;
  coverage: number;
  gap: number;
  /** p95 |отклонения|, px: насколько «шумная» линия. */
  noise: number;
  ms: number;
  /** Отклонение по отсчётам, px (со знаком). */
  dev?: number[];
  /** Допуск по отсчётам, px. */
  tol?: number[];
  /** Состояние отсчёта: 0 измерен, 1 исключён, 2 край линии, 3 линия не найдена. */
  state?: string;
  /** Измеренная линия, опорная линия и ось поиска в долях кадра. */
  edge?: Array<[number, number]>;
  ref?: Array<[number, number]>;
  axis?: Array<[number, number]>;
  samples?: number;
};

export type LinesLastResult = {
  ts_ms: number;
  frame_width: number;
  frame_height: number;
  reject: boolean;
  seam?: LineZoneResult;
  edge?: LineZoneResult;
};

export type LinesLastResponse = {
  analysis_profile?: string;
  last: LinesLastResult | null;
};
