import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import {
  createSettingErrorData,
  INITIAL_SETTING_DATA,
  loadSettingData,
  saveBrightnessData,
  saveSettingData,
  SAVING_SETTING_STATUS,
  updateAnalysisSettingField,
  updateSettingField,
} from "./SettingController";
import { ReferenceSetup } from "../ReferenceSetup";
import { ServerStream } from "../ServerStream";
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
};

export function SettingList({ selectedCameraId }: SettingListProps) {
  const [settingData, setSettingData] = useState(INITIAL_SETTING_DATA);
  const [isReferenceSetupOpen, setIsReferenceSetupOpen] = useState(false);
  const [isServerStreamOpen, setIsServerStreamOpen] = useState(false);
  const requestIdRef = useRef(0);

  const isBusy = settingData.status.state === "loading" || settingData.status.state === "saving";
  const canEditSettings = settingData.status.state === "ready";
  const brightnessScopeText = selectedCameraId === null ? "Все камеры" : `Камера ${selectedCameraId}`;
  const analysisScopeText = selectedCameraId === null ? "All camera products" : `Camera ${selectedCameraId} product`;
  const streamCameraId = selectedCameraId ?? SETTINGS_STREAM_CAMERA_ID;

  useLayoutEffect(() => {
    requestIdRef.current += 1;

    return () => {
      requestIdRef.current += 1;
    };
  }, [selectedCameraId]);

  useEffect(() => {
    let isActive = true;
    const requestId = requestIdRef.current;

    loadSettingData(selectedCameraId)
      .catch(createSettingErrorData)
      .then((nextSettingData) => {
        if (!isActive || requestId !== requestIdRef.current) {
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

    if (!canEditSettings) {
      return;
    }

    const formToSave = settingData.form;
    const requestId = ++requestIdRef.current;
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveSettingData(formToSave, selectedCameraId)
      .catch((error) => createSettingErrorData(error, formToSave))
      .then((nextSettingData) => {
        if (requestId === requestIdRef.current) {
          setSettingData(nextSettingData);
        }
      });
  };

  const handleBrightnessSave = () => {
    if (!canEditSettings) {
      return;
    }

    const { form, analysisProductTypes } = settingData;
    const requestId = ++requestIdRef.current;
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveBrightnessData(form, analysisProductTypes, selectedCameraId)
      .catch((error) => ({
        ...createSettingErrorData(error, form),
        analysisProductTypes,
      }))
      .then((nextSettingData) => {
        if (requestId === requestIdRef.current) {
          setSettingData(nextSettingData);
        }
      });
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
        <section className="setting-list__card setting-list__brightness">
          <div className="setting-list__card-header">
            <span>Яркость света</span>
            <strong className="setting-list__scope">{brightnessScopeText}</strong>
          </div>
          <label className="setting-list__control-row">
            <span className="setting-list__visually-hidden">Уровень яркости</span>
            <input
              type="range"
              min="0"
              max="100"
              step="1"
              value={settingData.form.brightnessPercent}
              disabled={!canEditSettings}
              onChange={handleFieldChange("brightnessPercent")}
            />
            <input
              className="setting-list__number"
              type="number"
              min="0"
              max="100"
              step="1"
              value={settingData.form.brightnessPercent}
              disabled={!canEditSettings}
              onChange={handleFieldChange("brightnessPercent")}
            />
          </label>
          <button
            className="setting-list__brightness-save"
            type="button"
            disabled={!canEditSettings}
            onClick={handleBrightnessSave}
          >
            Сохранить яркость
          </button>
        </section>

        <label className="setting-list__field setting-list__card">
          <span>Макс. смещение, мм</span>
          <input
            type="number"
            min="0"
            max="100"
            step="0.01"
            value={settingData.form.maxShiftMm}
            disabled={!canEditSettings}
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
                  disabled={!canEditSettings}
                  onChange={handleAnalysisFieldChange(field.name)}
                />
              </label>
            ))}
          </div>
        </section>

        <div className="setting-list__actions">
          <button
            className="setting-list__submit"
            type="submit"
            disabled={!canEditSettings}
          >
            Сохранить настройки
          </button>
          <button
            className="setting-list__submit setting-list__submit--secondary"
            type="button"
            disabled={isBusy}
            onClick={() => setIsReferenceSetupOpen(true)}
          >
            Задать эталон
          </button>
          <button
            className="setting-list__submit setting-list__submit--secondary"
            type="button"
            disabled={isBusy}
            onClick={() => setIsServerStreamOpen(true)}
          >
            Открыть стрим
          </button>
        </div>
      </form>

      {isReferenceSetupOpen && (
        <ReferenceSetup
          initialJointViewIndex={selectedCameraId}
          onClose={() => setIsReferenceSetupOpen(false)}
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
