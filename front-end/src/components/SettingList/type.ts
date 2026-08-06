import type { AnalysisSettings, LineDirection } from "../../shared/api";

export type SettingStatus = {
  state: "loading" | "ready" | "saving" | "error";
  text: string;
};

export type SettingForm = {
  brightnessPercent: number;
  constantFlashMode: boolean;
  maxShiftMm: number;
  jointSeamSegmentationEnabled: boolean;
  jointSeamSegmentationSensitivity: number;
  lineDirection: LineDirection;
  savedFramesCount: number;
  analysisSettings: AnalysisSettings;
};

export type SettingFieldName =
  | "brightnessPercent"
  | "maxShiftMm"
  | "savedFramesCount"
  | "jointSeamSegmentationEnabled"
  | "jointSeamSegmentationSensitivity";
export type AnalysisSettingFieldName = keyof AnalysisSettings;

export type SettingData = {
  status: SettingStatus;
  form: SettingForm;
  analysisProductTypes: string[];
};
