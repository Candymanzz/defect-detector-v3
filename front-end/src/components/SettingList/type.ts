import type { AnalysisSettings, LineDirection } from "../../shared/api";

export type SettingStatus = {
  state: "loading" | "ready" | "saving" | "error";
  text: string;
};

export type SettingForm = {
  brightnessPercent: number;
  maxShiftMm: number;
  lineDirection: LineDirection;
  analysisSettings: AnalysisSettings;
};

export type SettingFieldName = "brightnessPercent" | "maxShiftMm";
export type AnalysisSettingFieldName = keyof AnalysisSettings;

export type SettingData = {
  status: SettingStatus;
  form: SettingForm;
  analysisProductTypes: string[];
};
