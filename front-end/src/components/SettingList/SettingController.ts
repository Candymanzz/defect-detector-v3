import { orchestratorApi } from "../../shared/api";
import { setInspectionHistoryLimit } from "../MainOverview/MainController";
import type {
  AnalysisSettings,
  GeometryRuntimeConfig,
  LightBrightnessSettings,
  LightEndpointBrightness,
  LineDirection,
} from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import type { AnalysisSettingFieldName, SettingData, SettingFieldName, SettingForm, SettingStatus } from "./type";

const DEFAULT_MAX_SHIFT_MM = 0.5;
const DEFAULT_SAVED_FRAMES_COUNT = 20;
const MIN_BRIGHTNESS_PERCENT = 0;
const MAX_BRIGHTNESS_PERCENT = 100;
const MIN_MAX_SHIFT_MM = 0;
const MAX_MAX_SHIFT_MM = 100;
const FALLBACK_ANALYSIS_PRODUCT_TYPE = "reference-product";

const DEFAULT_ANALYSIS_SETTINGS: AnalysisSettings = {
  default_threshold: 0.25,
  use_patchcore: true,
  min_defect_area: 6,
  min_scratch_aspect: 3,
  min_diff_signal: 12,
  diff_percentile: 98,
  scratch_score_floor: 0.35,
  scratch_aspect_floor: 4.5,
  edge_suppress_factor: 0.2,
  text_min_contrast: 55,
  text_structure_threshold: 30,
  contrast_loss_boost: 2,
  contrast_loss_ref_grad: 40,
  contrast_loss_cur_grad: 15,
  enable_clahe: true,
  clahe_clip_limit: 1.2,
  fp_recheck_enabled: true,
  fp_trigger_diff_q90: 22,
};

export const INITIAL_SETTING_STATUS: SettingStatus = {
  state: "loading",
  text: "загрузка",
};

export const INITIAL_SETTING_FORM: SettingForm = {
  brightnessPercent: 0,
  constantFlashMode: false,
  maxShiftMm: DEFAULT_MAX_SHIFT_MM,
  lineDirection: "reverse",
  savedFramesCount: DEFAULT_SAVED_FRAMES_COUNT,
  analysisSettings: DEFAULT_ANALYSIS_SETTINGS,
};

export const INITIAL_SETTING_DATA: SettingData = {
  status: INITIAL_SETTING_STATUS,
  form: INITIAL_SETTING_FORM,
  analysisProductTypes: [],
};

export const SAVING_SETTING_STATUS: SettingStatus = {
  state: "saving",
  text: "сохранение",
};

export async function saveBrightnessData(
  form: SettingForm,
  analysisProductTypes: string[],
  selectedCameraId: number | null = null,
): Promise<SettingData> {
  const brightnessPercent = clampBrightness(form.brightnessPercent);
  const lightBrightness = await orchestratorApi.getLightBrightness();

  const response = await orchestratorApi.setLightBrightness(
    createBrightnessUpdate(lightBrightness, selectedCameraId, brightnessPercent),
  );

  if (response.hardware_errors?.length) {
    throw new Error(response.hardware_errors.join("; "));
  }

  return {
    status: {
      state: "ready",
      text: "яркость сохранена",
    },
    form: {
      ...form,
      brightnessPercent,
    },
    analysisProductTypes,
  };
}

export async function saveLineDirection(
  direction: LineDirection,
  form: SettingForm,
  analysisProductTypes: string[],
): Promise<SettingData> {
  const response = await orchestratorApi.setLineDirection(direction);
  return {
    status: {
      state: "ready",
      text: "направление сохранено",
    },
    form: {
      ...form,
      lineDirection: response.direction,
    },
    analysisProductTypes,
  };
}

export async function loadSettingData(selectedCameraId: number | null = null): Promise<SettingData> {
  const [lightBrightness, lightMode, geometryRuntime, analysisProductTypes, lineDirection, frameArchiveSettings] = await Promise.all([
    orchestratorApi.getLightBrightness(),
    orchestratorApi.getLightMode().catch(() => ({ constant: false, mode: "interval" as const })),
    orchestratorApi.getGeometryRuntime(selectedCameraId),
    loadAnalysisProductTypes(),
    orchestratorApi.getLineDirection().catch(() => ({ direction: "reverse" as const, source: "manual" as const })),
    orchestratorApi.getFrameArchiveSettings().catch(() => null),
  ]);
  const analysisResponse = await loadAnalysisSettings(selectedCameraId, analysisProductTypes);
  const analysisSettings = "settings" in analysisResponse ? analysisResponse.settings : analysisResponse;
  const resolvedProductTypes =
    "analysis_profile" in analysisResponse && analysisResponse.analysis_profile
      ? [analysisResponse.analysis_profile]
      : "product_type" in analysisResponse && analysisResponse.product_type
        ? [analysisResponse.product_type]
        : analysisProductTypes;
  const savedFramesCount = readSavedFramesCount(frameArchiveSettings);

  return {
    status: {
      state: "ready",
      text: "загружено",
    },
    form: {
      brightnessPercent: readBrightnessPercent(lightBrightness, selectedCameraId),
      constantFlashMode: lightMode.constant,
      maxShiftMm: readMaxShiftMm(geometryRuntime),
      lineDirection: lineDirection.direction,
      savedFramesCount,
      analysisSettings,
    },
    analysisProductTypes: resolvedProductTypes,
  };
}

export async function saveLightMode(
  constant: boolean,
  form: SettingForm,
  analysisProductTypes: string[],
): Promise<SettingData> {
  const response = await orchestratorApi.setLightMode(constant);
  return {
    status: { state: "ready", text: "Режим вспышек сохранён" },
    form: { ...form, constantFlashMode: response.constant },
    analysisProductTypes,
  };
}

export async function saveSettingData(form: SettingForm, selectedCameraId: number | null = null): Promise<SettingData> {
  const normalizedForm = normalizeSettingForm(form);
  const cameraList = selectedCameraId === null ? await orchestratorApi.listCameras() : null;
  const analysisSaveRequests =
    selectedCameraId === null
      ? createAllCameraAnalysisSaveRequests(cameraList?.cameras ?? [], normalizedForm.analysisSettings)
      : [
          saveWithContext(
            `Analysis settings camera ${selectedCameraId}`,
            orchestratorApi.setCameraAnalysisSettings(selectedCameraId, normalizedForm.analysisSettings),
          ),
        ];

  const [frameArchiveResponse] = await Promise.all([
    orchestratorApi.setFrameArchiveMaxFrames(normalizedForm.savedFramesCount),
    ...analysisSaveRequests,
  ]);
  setInspectionHistoryLimit(frameArchiveResponse.max_frames_per_camera);

  const nextData = await loadSettingData(selectedCameraId);

  return {
    ...nextData,
    form: {
      ...nextData.form,
      brightnessPercent: normalizedForm.brightnessPercent,
      savedFramesCount: frameArchiveResponse.max_frames_per_camera,
      analysisSettings: normalizedForm.analysisSettings,
    },
    status: {
      state: "ready",
      text: "сохранено",
    },
  };
}

function loadAnalysisSettings(selectedCameraId: number | null, fallbackProductTypes: string[]) {
  if (selectedCameraId !== null) {
    return orchestratorApi.getCameraAnalysisSettings(selectedCameraId);
  }

  return orchestratorApi.getAnalysisSettings(fallbackProductTypes[0] ?? FALLBACK_ANALYSIS_PRODUCT_TYPE);
}

export async function saveMaxShiftData(
  form: SettingForm,
  analysisProductTypes: string[],
  selectedCameraId: number | null = null,
): Promise<SettingData> {
  const normalizedForm = normalizeSettingForm(form);
  const cameraList = selectedCameraId === null ? await orchestratorApi.listCameras() : null;

  await saveWithContext(
    "Geometry settings",
    saveMaxShiftMm(normalizedForm.maxShiftMm, selectedCameraId, cameraList?.cameras ?? []),
  );

  return {
    status: {
      state: "ready",
      text: "max shift saved",
    },
    form: {
      ...form,
      maxShiftMm: normalizedForm.maxShiftMm,
    },
    analysisProductTypes,
  };
}

export async function saveSavedFramesData(
  form: SettingForm,
  analysisProductTypes: string[],
): Promise<SettingData> {
  const normalizedForm = normalizeSettingForm(form);
  const response = await orchestratorApi.setFrameArchiveMaxFrames(normalizedForm.savedFramesCount);
  setInspectionHistoryLimit(response.max_frames_per_camera);

  return {
    status: {
      state: "ready",
      text: "количество кадров сохранено",
    },
    form: {
      ...form,
      savedFramesCount: response.max_frames_per_camera,
    },
    analysisProductTypes,
  };
}

export function createSettingErrorData(error: unknown, form: SettingForm = INITIAL_SETTING_FORM): SettingData {
  return {
    status: {
      state: "error",
      text: errorMessage(error),
    },
    form,
    analysisProductTypes: [],
  };
}

export function updateSettingField(form: SettingForm, fieldName: SettingFieldName, rawValue: string): SettingForm {
  const currentValue = form[fieldName];
  const parsedValue = parseInputNumber(rawValue, currentValue);

  return normalizeSettingForm({
    ...form,
    [fieldName]: parsedValue,
  });
}

export function updateAnalysisSettingField(
  form: SettingForm,
  fieldName: AnalysisSettingFieldName,
  rawValue: string | boolean,
): SettingForm {
  const currentValue = form.analysisSettings[fieldName];
  const nextValue =
    typeof currentValue === "boolean" ? Boolean(rawValue) : parseInputNumber(String(rawValue), currentValue);

  return normalizeSettingForm({
    ...form,
    analysisSettings: {
      ...form.analysisSettings,
      [fieldName]: nextValue,
    },
  });
}

function normalizeSettingForm(form: SettingForm): SettingForm {
  return {
    brightnessPercent: clampBrightness(form.brightnessPercent),
    constantFlashMode: Boolean(form.constantFlashMode),
    maxShiftMm: clampMaxShiftMm(form.maxShiftMm),
    lineDirection: form.lineDirection === "reverse" ? "reverse" : "forward",
    savedFramesCount: clampSavedFramesCount(form.savedFramesCount),
    analysisSettings: normalizeAnalysisSettings(form.analysisSettings),
  };
}

function normalizeAnalysisSettings(settings: AnalysisSettings): AnalysisSettings {
  return {
    default_threshold: clampNumber(
      toFiniteNumber(settings.default_threshold, DEFAULT_ANALYSIS_SETTINGS.default_threshold),
      Number.MIN_VALUE,
      1,
    ),
    use_patchcore: toBoolean(settings.use_patchcore, DEFAULT_ANALYSIS_SETTINGS.use_patchcore),
    min_defect_area: Math.max(
      1,
      Math.round(toFiniteNumber(settings.min_defect_area, DEFAULT_ANALYSIS_SETTINGS.min_defect_area)),
    ),
    min_scratch_aspect: Math.max(
      1,
      toFiniteNumber(settings.min_scratch_aspect, DEFAULT_ANALYSIS_SETTINGS.min_scratch_aspect),
    ),
    min_diff_signal: Math.max(0, toFiniteNumber(settings.min_diff_signal, DEFAULT_ANALYSIS_SETTINGS.min_diff_signal)),
    diff_percentile: clampNumber(
      toFiniteNumber(settings.diff_percentile, DEFAULT_ANALYSIS_SETTINGS.diff_percentile),
      50,
      100,
    ),
    scratch_score_floor: clampNumber(
      toFiniteNumber(settings.scratch_score_floor, DEFAULT_ANALYSIS_SETTINGS.scratch_score_floor),
      0,
      1,
    ),
    scratch_aspect_floor: Math.max(
      1,
      toFiniteNumber(settings.scratch_aspect_floor, DEFAULT_ANALYSIS_SETTINGS.scratch_aspect_floor),
    ),
    edge_suppress_factor: clampNumber(
      toFiniteNumber(settings.edge_suppress_factor, DEFAULT_ANALYSIS_SETTINGS.edge_suppress_factor),
      0,
      1,
    ),
    text_min_contrast: clampNumber(
      Math.round(toFiniteNumber(settings.text_min_contrast, DEFAULT_ANALYSIS_SETTINGS.text_min_contrast)),
      0,
      255,
    ),
    text_structure_threshold: clampNumber(
      Math.round(toFiniteNumber(settings.text_structure_threshold, DEFAULT_ANALYSIS_SETTINGS.text_structure_threshold)),
      0,
      255,
    ),
    contrast_loss_boost: Math.max(
      1,
      toFiniteNumber(settings.contrast_loss_boost, DEFAULT_ANALYSIS_SETTINGS.contrast_loss_boost),
    ),
    contrast_loss_ref_grad: Math.max(
      0,
      toFiniteNumber(settings.contrast_loss_ref_grad, DEFAULT_ANALYSIS_SETTINGS.contrast_loss_ref_grad),
    ),
    contrast_loss_cur_grad: Math.max(
      0,
      toFiniteNumber(settings.contrast_loss_cur_grad, DEFAULT_ANALYSIS_SETTINGS.contrast_loss_cur_grad),
    ),
    enable_clahe: toBoolean(settings.enable_clahe, DEFAULT_ANALYSIS_SETTINGS.enable_clahe),
    clahe_clip_limit: Math.max(
      Number.MIN_VALUE,
      toFiniteNumber(settings.clahe_clip_limit, DEFAULT_ANALYSIS_SETTINGS.clahe_clip_limit),
    ),
    fp_recheck_enabled: toBoolean(settings.fp_recheck_enabled, DEFAULT_ANALYSIS_SETTINGS.fp_recheck_enabled),
    fp_trigger_diff_q90: Math.max(
      0,
      toFiniteNumber(settings.fp_trigger_diff_q90, DEFAULT_ANALYSIS_SETTINGS.fp_trigger_diff_q90),
    ),
  };
}

function readBrightnessPercent(lightBrightness: LightBrightnessSettings, selectedCameraId: number | null) {
  const cameraEndpoint = resolveCameraBrightnessEndpoint(lightBrightness, selectedCameraId);

  if (cameraEndpoint) {
    return clampBrightness(cameraEndpoint.brightness_percent);
  }

  return clampBrightness(
    firstFiniteNumber(
      [
        lightBrightness.brightness_percent,
        lightBrightness.default_brightness_percent,
        lightBrightness.endpoints?.[0]?.brightness_percent,
      ],
      0,
    ),
  );
}

function createBrightnessUpdate(
  lightBrightness: LightBrightnessSettings,
  selectedCameraId: number | null,
  brightnessPercent: number,
) {
  const cameraEndpoint = resolveCameraBrightnessEndpoint(lightBrightness, selectedCameraId);

  if (selectedCameraId === null) {
    return brightnessPercent;
  }

  if (!cameraEndpoint) {
    throw new Error(`Light endpoint for camera ${selectedCameraId} was not found`);
  }

  return {
    endpoints: {
      [cameraEndpoint.id]: brightnessPercent,
    },
  };
}

function resolveCameraBrightnessEndpoint(
  lightBrightness: LightBrightnessSettings,
  selectedCameraId: number | null,
): LightEndpointBrightness | undefined {
  if (selectedCameraId === null) {
    return undefined;
  }

  const endpoints = lightBrightness.endpoints ?? [];
  const cameraEndpoint = endpoints.find((endpoint) => endpoint.camera_ids?.includes(selectedCameraId));

  if (cameraEndpoint) {
    return cameraEndpoint;
  }

  const cameraIdText = String(selectedCameraId);
  const expectedIds = new Set([
    cameraIdText,
    `camera-${cameraIdText}`,
    `camera_${cameraIdText}`,
    `cam-${cameraIdText}`,
    `cam_${cameraIdText}`,
    `light-${cameraIdText}`,
    `light_${cameraIdText}`,
    `light-camera-${cameraIdText}`,
    `light_camera_${cameraIdText}`,
  ]);

  return endpoints.find((endpoint) => expectedIds.has(endpoint.id)) ?? endpoints[selectedCameraId];
}

function readMaxShiftMm(geometryRuntime: GeometryRuntimeConfig) {
  return clampMaxShiftMm(
    firstFiniteNumber(
      [
        geometryRuntime.runtimeOverrides.max_shift_mm,
        geometryRuntime.runtimeOverrides.maxShiftMm,
        geometryRuntime.effectiveForNextGeometryInspect.max_shift_mm,
        geometryRuntime.effectiveForNextGeometryInspect.maxShiftMm,
      ],
      DEFAULT_MAX_SHIFT_MM,
    ),
  );
}

function readSavedFramesCount(frameArchiveSettings: { max_frames_per_camera?: number; max_allowed_frames_per_camera?: number } | null) {
  return clampSavedFramesCount(
    frameArchiveSettings?.max_frames_per_camera ?? DEFAULT_SAVED_FRAMES_COUNT,
    frameArchiveSettings?.max_allowed_frames_per_camera,
  );
}

async function saveMaxShiftMm(maxShiftMm: number, selectedCameraId: number | null, cameraIds: number[]) {
  const update = { max_shift_mm: maxShiftMm };

  if (selectedCameraId !== null) {
    await orchestratorApi.patchGeometryRuntime(update, selectedCameraId);
    return;
  }

  await Promise.all([
    orchestratorApi.patchGeometryRuntime(update),
    ...cameraIds.map((cameraId) => orchestratorApi.patchGeometryRuntime(update, cameraId)),
  ]);
}

async function loadAnalysisProductTypes() {
  try {
    const cameraList = await orchestratorApi.listCameras();
    const snapshots = await Promise.allSettled(
      cameraList.cameras.map((cameraId) => orchestratorApi.getLatestSnapshot(cameraId)),
    );
    const productTypes = snapshots
      .map((result) => (result.status === "fulfilled" ? result.value.productType : ""))
      .filter((productType): productType is string => Boolean(productType.trim()));

    return Array.from(new Set(productTypes));
  } catch {
    return [FALLBACK_ANALYSIS_PRODUCT_TYPE];
  }
}

function resolveAnalysisProductTypesToSave(fallbackProductTypes: string[]) {
  return fallbackProductTypes.length > 0 ? fallbackProductTypes : [FALLBACK_ANALYSIS_PRODUCT_TYPE];
}

function createAllCameraAnalysisSaveRequests(cameraIds: number[], settings: AnalysisSettings) {
  if (cameraIds.length === 0) {
    return resolveAnalysisProductTypesToSave([]).map((analysisProfile) =>
      saveWithContext(
        `Analysis settings profile ${analysisProfile}`,
        orchestratorApi.setAnalysisSettings(analysisProfile, settings),
      ),
    );
  }

  return cameraIds.map((cameraId) =>
    saveWithContext(
      `Analysis settings camera ${cameraId}`,
      orchestratorApi.setCameraAnalysisSettings(cameraId, settings),
    ),
  );
}

function parseInputNumber(rawValue: string, fallback: number) {
  if (!rawValue.trim()) {
    return fallback;
  }

  return toFiniteNumber(rawValue, fallback);
}

function firstFiniteNumber(values: unknown[], fallback: number) {
  for (const value of values) {
    const nextValue = toFiniteNumber(value, Number.NaN);

    if (Number.isFinite(nextValue)) {
      return nextValue;
    }
  }

  return fallback;
}

function toFiniteNumber(value: unknown, fallback: number) {
  const nextValue = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  return Number.isFinite(nextValue) ? nextValue : fallback;
}

function toBoolean(value: unknown, fallback: boolean) {
  if (typeof value === "boolean") {
    return value;
  }

  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();

    if (normalized === "true") {
      return true;
    }

    if (normalized === "false") {
      return false;
    }
  }

  return fallback;
}

async function saveWithContext<T>(label: string, request: Promise<T>) {
  try {
    return await request;
  } catch (error) {
    const contextualError = new Error(`${label}: ${errorMessage(error)}`);
    Object.defineProperty(contextualError, "cause", { value: error });
    throw contextualError;
  }
}

function clampBrightness(value: number) {
  return clampNumber(Math.round(value), MIN_BRIGHTNESS_PERCENT, MAX_BRIGHTNESS_PERCENT);
}

function clampMaxShiftMm(value: number) {
  return clampNumber(value, MIN_MAX_SHIFT_MM, MAX_MAX_SHIFT_MM);
}

function clampSavedFramesCount(value: number, maxAllowed = 100) {
  return clampNumber(Math.round(value), 0, Math.max(0, maxAllowed));
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}
