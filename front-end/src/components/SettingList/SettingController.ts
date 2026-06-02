import { orchestratorApi } from "../../shared/api";
import type { GeometryRuntimeConfig, LightBrightnessSettings, LightEndpointBrightness } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import type { SettingData, SettingFieldName, SettingForm, SettingStatus } from "./type";

const DEFAULT_MAX_SHIFT_MM = 0.5;
const MIN_BRIGHTNESS_PERCENT = 0;
const MAX_BRIGHTNESS_PERCENT = 100;
const MIN_MAX_SHIFT_MM = 0;
const MAX_MAX_SHIFT_MM = 100;

export const INITIAL_SETTING_STATUS: SettingStatus = {
  state: "loading",
  text: "загрузка",
};

export const INITIAL_SETTING_FORM: SettingForm = {
  brightnessPercent: 0,
  maxShiftMm: DEFAULT_MAX_SHIFT_MM,
};

export const INITIAL_SETTING_DATA: SettingData = {
  status: INITIAL_SETTING_STATUS,
  form: INITIAL_SETTING_FORM,
};

export const SAVING_SETTING_STATUS: SettingStatus = {
  state: "saving",
  text: "сохранение",
};

export async function loadSettingData(selectedCameraId: number | null = null): Promise<SettingData> {
  const [lightBrightness, geometryRuntime] = await Promise.all([
    orchestratorApi.getLightBrightness(),
    orchestratorApi.getGeometryRuntime(),
  ]);

  return {
    status: {
      state: "ready",
      text: "загружено",
    },
    form: {
      brightnessPercent: readBrightnessPercent(lightBrightness, selectedCameraId),
      maxShiftMm: readMaxShiftMm(geometryRuntime),
    },
  };
}

export async function saveSettingData(form: SettingForm, selectedCameraId: number | null = null): Promise<SettingData> {
  const normalizedForm = normalizeSettingForm(form);
  const [geometryRuntime, lightBrightness] = await Promise.all([
    orchestratorApi.getGeometryRuntime(),
    orchestratorApi.getLightBrightness(),
  ]);

  await Promise.all([
    orchestratorApi.setLightBrightness(
      createBrightnessUpdate(lightBrightness, selectedCameraId, normalizedForm.brightnessPercent),
    ),
    orchestratorApi.replaceGeometryRuntime(createGeometryRuntimeOverrides(geometryRuntime, normalizedForm.maxShiftMm)),
  ]);

  const nextData = await loadSettingData(selectedCameraId);

  return {
    ...nextData,
    form: {
      ...nextData.form,
      brightnessPercent: normalizedForm.brightnessPercent,
    },
    status: {
      state: "ready",
      text: "сохранено",
    },
  };
}

export function createSettingErrorData(error: unknown, form: SettingForm = INITIAL_SETTING_FORM): SettingData {
  return {
    status: {
      state: "error",
      text: errorMessage(error),
    },
    form,
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

function normalizeSettingForm(form: SettingForm): SettingForm {
  return {
    brightnessPercent: clampBrightness(form.brightnessPercent),
    maxShiftMm: clampMaxShiftMm(form.maxShiftMm),
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
