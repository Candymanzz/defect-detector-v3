import { useEffect, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import {
  createSettingErrorData,
  INITIAL_SETTING_DATA,
  loadSettingData,
  saveSettingData,
  SAVING_SETTING_STATUS,
  updateAnalysisSettingField,
  updateSettingField,
} from "./SettingController";
import { ReferenceSetup } from "../ReferenceSetup";
import { ServerStream } from "../ServerStream";
import { Button } from "../../shared/ui/Button";
import type { AnalysisSettingFieldName, SettingFieldName } from "./type";
import "./SettingList.css";

const SETTINGS_STREAM_CAMERA_ID = 0;

const ANALYSIS_SETTING_FIELDS: Array<{
  name: AnalysisSettingFieldName;
  label: string;
  type: "number" | "checkbox";
  min?: number;
  max?: number;
  step?: string;
}> = [
  { name: "default_threshold", label: "Default threshold", type: "number", min: 0.01, max: 1, step: "0.01" },
  { name: "use_patchcore", label: "Use PatchCore", type: "checkbox" },
  { name: "min_defect_area", label: "Min defect area", type: "number", min: 1, step: "1" },
  { name: "min_scratch_aspect", label: "Min scratch aspect", type: "number", min: 1, step: "0.1" },
  { name: "min_diff_signal", label: "Min diff signal", type: "number", min: 0, step: "0.1" },
  { name: "diff_percentile", label: "Diff percentile", type: "number", min: 50, max: 100, step: "0.1" },
  { name: "scratch_score_floor", label: "Scratch score floor", type: "number", min: 0, max: 1, step: "0.01" },
  { name: "scratch_aspect_floor", label: "Scratch aspect floor", type: "number", min: 1, step: "0.1" },
  { name: "edge_suppress_factor", label: "Edge suppress factor", type: "number", min: 0, max: 1, step: "0.01" },
  { name: "text_min_contrast", label: "Text min contrast", type: "number", min: 0, max: 255, step: "1" },
  { name: "text_structure_threshold", label: "Text structure threshold", type: "number", min: 0, max: 255, step: "1" },
  { name: "contrast_loss_boost", label: "Contrast loss boost", type: "number", min: 1, step: "0.1" },
  { name: "contrast_loss_ref_grad", label: "Contrast loss ref grad", type: "number", min: 0, step: "0.1" },
  { name: "contrast_loss_cur_grad", label: "Contrast loss cur grad", type: "number", min: 0, step: "0.1" },
  { name: "enable_clahe", label: "Enable CLAHE", type: "checkbox" },
  { name: "clahe_clip_limit", label: "CLAHE clip limit", type: "number", min: 0.01, step: "0.1" },
  { name: "fp_recheck_enabled", label: "FP recheck enabled", type: "checkbox" },
  { name: "fp_trigger_diff_q90", label: "FP trigger diff q90", type: "number", min: 0, step: "0.1" },
];

type SettingListProps = {
  selectedCameraId: number | null;
  onPreviewPauseChange?: (isPaused: boolean) => void;
};

export function SettingList({ selectedCameraId, onPreviewPauseChange }: SettingListProps) {
  const [settingData, setSettingData] = useState(INITIAL_SETTING_DATA);
  const [isReferenceSetupOpen, setIsReferenceSetupOpen] = useState(false);
  const [isServerStreamOpen, setIsServerStreamOpen] = useState(false);

  const isBusy = settingData.status.state === "loading" || settingData.status.state === "saving";
  const brightnessScopeText = selectedCameraId === null ? "Все камеры" : `Камера ${selectedCameraId}`;
  const analysisScopeText = selectedCameraId === null ? "Все камеры" : `Камера ${selectedCameraId}`;
  const streamCameraId = selectedCameraId ?? SETTINGS_STREAM_CAMERA_ID;

  useEffect(() => {
    return () => onPreviewPauseChange?.(false);
  }, [onPreviewPauseChange]);

  useEffect(() => {
    let isActive = true;

    loadSettingData(selectedCameraId)
      .catch(createSettingErrorData)
      .then((nextSettingData) => {
        if (!isActive) {
          return;
        }

        setSettingData(nextSettingData);
      });

    return () => {
      isActive = false;
    };
  }, [selectedCameraId]);

  const handleFieldChange = (fieldName: SettingFieldName) => (event: ChangeEvent<HTMLInputElement>) => {
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      form: updateSettingField(currentSettingData.form, fieldName, event.target.value),
    }));
  };

  const handleAnalysisFieldChange = (fieldName: AnalysisSettingFieldName) => (event: ChangeEvent<HTMLInputElement>) => {
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      form: updateAnalysisSettingField(
        currentSettingData.form,
        fieldName,
        event.target.type === "checkbox" ? event.target.checked : event.target.value,
      ),
    }));
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const formToSave = settingData.form;
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveSettingData(formToSave, selectedCameraId)
      .catch((error) => createSettingErrorData(error, formToSave))
      .then(setSettingData);
  };

  return (
    <aside
      className="setting-list"
      aria-label="Настройки"
    >
      <div className="setting-list__header">
        <h2>Настройки</h2>
        <strong data-status={settingData.status.state}>{settingData.status.text}</strong>
      </div>

      <form
        className="setting-list__form"
        onSubmit={handleSubmit}
      >
        <label className="setting-list__field-light">
          <span>Яркость света</span>
          <strong className="setting-list__scope">{brightnessScopeText}</strong>
          <div className="setting-list__control-row">
            <input
              type="range"
              min="0"
              max="100"
              step="1"
              value={settingData.form.brightnessPercent}
              disabled={isBusy}
              onChange={handleFieldChange("brightnessPercent")}
            />
            <input
              className="setting-list__number"
              type="number"
              min="0"
              max="100"
              step="1"
              value={settingData.form.brightnessPercent}
              disabled={isBusy}
              onChange={handleFieldChange("brightnessPercent")}
            />
          </div>
        </label>

        <label className="setting-list__field">
          <span>Макс. смещение, мм</span>
          <input
            type="number"
            min="0"
            max="100"
            step="0.01"
            value={settingData.form.maxShiftMm}
            disabled={isBusy}
            onChange={handleFieldChange("maxShiftMm")}
          />
        </label>

        <section className="setting-list__analysis">
          <div className="setting-list__section-header">
            <h3>Analysis settings</h3>
            <strong>{analysisScopeText}</strong>
          </div>
          <div className="setting-list__analysis-grid">
            {ANALYSIS_SETTING_FIELDS.map((field) => (
              <label
                key={field.name}
                className={
                  field.type === "checkbox"
                    ? "setting-list__field setting-list__field--checkbox"
                    : "setting-list__field"
                }
              >
                <span>{field.label}</span>
                <input
                  type={field.type}
                  min={field.min}
                  max={field.max}
                  step={field.step}
                  checked={
                    field.type === "checkbox" ? Boolean(settingData.form.analysisSettings[field.name]) : undefined
                  }
                  value={field.type === "number" ? Number(settingData.form.analysisSettings[field.name]) : undefined}
                  disabled={isBusy}
                  onChange={handleAnalysisFieldChange(field.name)}
                />
              </label>
            ))}
          </div>
        </section>

        <Button
          type="submit"
          disabled={isBusy}
          fullWidth
          variant="primary"
        >
          Сохранить настройки
        </Button>
        <Button
          type="button"
          disabled={isBusy}
          fullWidth
          variant="ghost"
          onClick={() => {
            setIsReferenceSetupOpen(true);
            onPreviewPauseChange?.(true);
          }}
        >
          Задать эталон
        </Button>
        <Button
          type="button"
          disabled={isBusy}
          fullWidth
          variant="ghost"
          onClick={() => setIsServerStreamOpen(true)}
        >
          Открыть стрим
        </Button>
      </form>

      {isReferenceSetupOpen && (
        <ReferenceSetup
          initialJointViewIndex={selectedCameraId}
          onClose={() => {
            setIsReferenceSetupOpen(false);
            onPreviewPauseChange?.(false);
          }}
        />
      )}
      {isServerStreamOpen && (
        <ServerStream
          isOpen
          cameraId={streamCameraId}
          title={`Camera ${streamCameraId}`}
          onClose={() => setIsServerStreamOpen(false)}
        />
      )}
    </aside>
  );
}
