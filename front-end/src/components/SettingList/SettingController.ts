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

export const SAVING_SETTING_STATUS: SettingStatus = {
  state: "saving",
  text: "сохранение",
};

export async function loadSettingData(selectedCameraId: number | null = null): Promise<SettingData> {
  const [lightBrightness, geometryRuntime, analysisProductTypes] = await Promise.all([
    orchestratorApi.getLightBrightness(),
    orchestratorApi.getGeometryRuntime(),
    loadAnalysisProductTypes(),
  ]);
  const analysisResponse = await loadAnalysisSettings(selectedCameraId, analysisProductTypes)
    .catch(() => DEFAULT_ANALYSIS_SETTINGS);
  const analysisSettings =
    "settings" in analysisResponse ? analysisResponse.settings : analysisResponse;
  const resolvedProductTypes =
    "product_type" in analysisResponse ? [analysisResponse.product_type] : analysisProductTypes;

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
    analysisProductTypes: resolvedProductTypes,
  };
}

export async function saveSettingData(form: SettingForm, selectedCameraId: number | null = null): Promise<SettingData> {
  const normalizedForm = normalizeSettingForm(form);
  const [geometryRuntime, lightBrightness, analysisProductTypes] = await Promise.all([
    orchestratorApi.getGeometryRuntime(),
    orchestratorApi.getLightBrightness(),
    loadAnalysisProductTypes(),
  ]);
  const analysisSaveRequests =
    selectedCameraId === null
      ? resolveAnalysisProductTypesToSave(analysisProductTypes).map((productType) =>
          orchestratorApi.setAnalysisSettings(productType, normalizedForm.analysisSettings),
        )
      : [orchestratorApi.setCameraAnalysisSettings(selectedCameraId, normalizedForm.analysisSettings)];

  await Promise.all([
    orchestratorApi.setLightBrightness(
      createBrightnessUpdate(lightBrightness, selectedCameraId, normalizedForm.brightnessPercent),
    ),
    orchestratorApi.replaceGeometryRuntime(createGeometryRuntimeOverrides(geometryRuntime, normalizedForm.maxShiftMm)),
    ...analysisSaveRequests,
  ]);

  const nextData = await loadSettingData(selectedCameraId);

  return {
    ...nextData,
    form: {
      ...nextData.form,
      brightnessPercent: normalizedForm.brightnessPercent,
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

function resolveAnalysisProductTypesToSave(fallbackProductTypes: string[]) {
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
