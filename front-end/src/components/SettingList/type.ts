export type SettingStatus = {
  state: "loading" | "ready" | "saving" | "error";
  text: string;
};

export type SettingForm = {
  brightnessPercent: number;
  maxShiftMm: number;
};

export type SettingFieldName = keyof SettingForm;

export type SettingData = {
  status: SettingStatus;
  form: SettingForm;
};
