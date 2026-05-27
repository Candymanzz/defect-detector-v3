import { orchestratorApi } from "../../shared/api";
import type { GeometryRuntimeConfig } from "../../shared/api";
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

export async function loadSettingData(): Promise<SettingData> {
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
      brightnessPercent: clampBrightness(lightBrightness.brightness_percent),
      maxShiftMm: readMaxShiftMm(geometryRuntime),
    },
  };
}

export async function saveSettingData(form: SettingForm): Promise<SettingData> {
  const normalizedForm = normalizeSettingForm(form);
  const geometryRuntime = await orchestratorApi.getGeometryRuntime();

  await Promise.all([
    orchestratorApi.setLightBrightness(normalizedForm.brightnessPercent),
    orchestratorApi.replaceGeometryRuntime(createGeometryRuntimeOverrides(geometryRuntime, normalizedForm.maxShiftMm)),
  ]);

  const nextData = await loadSettingData();

  return {
    ...nextData,
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
