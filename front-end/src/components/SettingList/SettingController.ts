import { orchestratorApi } from "../../shared/api";
import type { AnalysisSettings, GeometryRuntimeConfig, LightBrightnessSettings, LightEndpointBrightness } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import type { AnalysisSettingFieldName, SettingData, SettingFieldName, SettingForm, SettingStatus } from "./type";

const DEFAULT_MAX_SHIFT_MM = 0.5;
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
  maxShiftMm: DEFAULT_MAX_SHIFT_MM,
  analysisSettings: DEFAULT_ANALYSIS_SETTINGS,
};

export const INITIAL_SETTING_DATA: SettingData = {
  status: INITIAL_SETTING_STATUS,
  form: INITIAL_SETTING_FORM,
  analysisProductTypes: [],
};

export async function loadSettingData(selectedCameraId: number | null = null): Promise<SettingData> {
  const [lightBrightness, geometryRuntime, analysisProductTypes] = await Promise.all([
    orchestratorApi.getLightBrightness(),
    orchestratorApi.getGeometryRuntime(),
    loadAnalysisProductTypes(),
  ]);
  const analysisProductType = await resolveAnalysisProductType(selectedCameraId, analysisProductTypes);
  const analysisSettings = await orchestratorApi
    .getAnalysisSettings(analysisProductType)
    .then((response) => response.settings)
    .catch(() => DEFAULT_ANALYSIS_SETTINGS);

  return {
    status: {
      state: "ready",
      text: "загружено",
    },
    form: {
      brightnessPercent: readBrightnessPercent(lightBrightness, selectedCameraId),
      maxShiftMm: readMaxShiftMm(geometryRuntime),
      analysisSettings,
    },
    analysisProductTypes,
  };
}

export async function saveBrightnessSetting(brightnessPercent: number, selectedCameraId: number | null = null) {
  const normalizedBrightness = clampBrightness(brightnessPercent);
  const lightBrightness = await orchestratorApi.getLightBrightness();

  await orchestratorApi.setLightBrightness(
    createBrightnessUpdate(lightBrightness, selectedCameraId, normalizedBrightness),
  );

  return normalizedBrightness;
}

export async function saveGeometrySetting(maxShiftMm: number) {
  const normalizedMaxShift = clampMaxShiftMm(maxShiftMm);
  const geometryRuntime = await orchestratorApi.getGeometryRuntime();

  await orchestratorApi.replaceGeometryRuntime(
    createGeometryRuntimeOverrides(geometryRuntime, normalizedMaxShift),
  );

  return normalizedMaxShift;
}

export async function saveAnalysisSettingData(
  analysisSettings: AnalysisSettings,
  selectedCameraId: number | null = null,
) {
  const normalizedSettings = normalizeAnalysisSettings(analysisSettings);
  const analysisProductTypes = await loadAnalysisProductTypes();
  const productTypesToSave = await resolveAnalysisProductTypesToSave(selectedCameraId, analysisProductTypes);

  await Promise.all(
    productTypesToSave.map((productType) =>
      orchestratorApi.setAnalysisSettings(productType, normalizedSettings),
    ),
  );

  return {
    analysisSettings: normalizedSettings,
    analysisProductTypes: productTypesToSave,
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
  const nextValue = typeof currentValue === "boolean" ? Boolean(rawValue) : parseInputNumber(String(rawValue), currentValue);

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
    maxShiftMm: clampMaxShiftMm(form.maxShiftMm),
    analysisSettings: normalizeAnalysisSettings(form.analysisSettings),
  };
}

function normalizeAnalysisSettings(settings: AnalysisSettings): AnalysisSettings {
  return {
    default_threshold: clampNumber(settings.default_threshold, Number.MIN_VALUE, 1),
    use_patchcore: settings.use_patchcore,
    min_defect_area: Math.max(1, Math.round(settings.min_defect_area)),
    min_scratch_aspect: Math.max(1, settings.min_scratch_aspect),
    min_diff_signal: Math.max(0, settings.min_diff_signal),
    diff_percentile: clampNumber(settings.diff_percentile, 50, 100),
    scratch_score_floor: clampNumber(settings.scratch_score_floor, 0, 1),
    scratch_aspect_floor: Math.max(1, settings.scratch_aspect_floor),
    edge_suppress_factor: clampNumber(settings.edge_suppress_factor, 0, 1),
    text_min_contrast: clampNumber(Math.round(settings.text_min_contrast), 0, 255),
    text_structure_threshold: clampNumber(Math.round(settings.text_structure_threshold), 0, 255),
    contrast_loss_boost: Math.max(1, settings.contrast_loss_boost),
    contrast_loss_ref_grad: Math.max(0, settings.contrast_loss_ref_grad),
    contrast_loss_cur_grad: Math.max(0, settings.contrast_loss_cur_grad),
    enable_clahe: settings.enable_clahe,
    clahe_clip_limit: Math.max(Number.MIN_VALUE, settings.clahe_clip_limit),
    fp_recheck_enabled: settings.fp_recheck_enabled,
    fp_trigger_diff_q90: Math.max(0, settings.fp_trigger_diff_q90),
  };
}

function readBrightnessPercent(lightBrightness: LightBrightnessSettings, selectedCameraId: number | null) {
  const cameraEndpoint = resolveCameraBrightnessEndpoint(lightBrightness, selectedCameraId);

  if (cameraEndpoint) {
    return clampBrightness(cameraEndpoint.brightness_percent);
  }

  const commonEndpointBrightness = readCommonEndpointBrightness(lightBrightness.endpoints ?? []);
  if (selectedCameraId === null && commonEndpointBrightness !== undefined) {
    return clampBrightness(commonEndpointBrightness);
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

  return endpoints.find((endpoint) => expectedIds.has(endpoint.id));
}

function readCommonEndpointBrightness(endpoints: LightEndpointBrightness[]) {
  const brightnessValues = endpoints
    .map((endpoint) => endpoint.brightness_percent)
    .filter((value) => Number.isFinite(value));

  if (brightnessValues.length === 0) {
    return undefined;
  }

  return brightnessValues.every((value) => value === brightnessValues[0]) ? brightnessValues[0] : undefined;
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

function createGeometryRuntimeOverrides(geometryRuntime: GeometryRuntimeConfig, maxShiftMm: number) {
  const nextOverrides: Record<string, unknown> = {
    ...geometryRuntime.runtimeOverrides,
  };

  delete nextOverrides.maxShiftMm;
  delete nextOverrides.max_shift_mm;
  nextOverrides.max_shift_mm = maxShiftMm;

  return nextOverrides;
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

async function resolveAnalysisProductType(selectedCameraId: number | null, fallbackProductTypes: string[]) {
  if (selectedCameraId !== null) {
    try {
      const snapshot = await orchestratorApi.getLatestSnapshot(selectedCameraId);
      if (snapshot.productType.trim()) {
        return snapshot.productType;
      }
    } catch {
      // fall through to shared fallback
    }
  }

  return fallbackProductTypes[0] ?? FALLBACK_ANALYSIS_PRODUCT_TYPE;
}

async function resolveAnalysisProductTypesToSave(selectedCameraId: number | null, fallbackProductTypes: string[]) {
  if (selectedCameraId !== null) {
    return [await resolveAnalysisProductType(selectedCameraId, fallbackProductTypes)];
  }

  return fallbackProductTypes.length > 0 ? fallbackProductTypes : [FALLBACK_ANALYSIS_PRODUCT_TYPE];
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

function clampBrightness(value: number) {
  return clampNumber(Math.round(value), MIN_BRIGHTNESS_PERCENT, MAX_BRIGHTNESS_PERCENT);
}

function clampMaxShiftMm(value: number) {
  return clampNumber(value, MIN_MAX_SHIFT_MM, MAX_MAX_SHIFT_MM);
}

function clampNumber(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}
