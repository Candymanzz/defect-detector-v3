import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ChangeEvent, CSSProperties, FormEvent } from "react";
import {
  createSettingErrorData,
  INITIAL_SETTING_DATA,
  loadSettingData,
  saveBrightnessData,
  saveLightMode,
  saveMaxShiftData,
  saveSavedFramesData,
  saveSettingData,
  SAVING_SETTING_STATUS,
  updateAnalysisSettingField,
  updateSettingField,
} from "./SettingController";
import { CameraSettingsModal } from "../CameraSettingsModal";
import { ReferenceSetup } from "../ReferenceSetup";
import { ServerStream } from "../ServerStream";
import { orchestratorApi } from "../../shared/api";
import benchmarkIconUrl from "../../shared/assets/images/benchmark.svg";
import camerasIconUrl from "../../shared/assets/images/cameras.svg";
import streamIconUrl from "../../shared/assets/images/stream.svg";
import resetIconUrl from "../../shared/assets/images/reset.svg";
import { errorMessage } from "../../shared/lib/errors";
import { clearReferenceImages } from "../../shared/referenceImages";
import { Button } from "../../shared/ui/Button";
import type { InspectionStats } from "../MainOverview/type";
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
  { name: "default_threshold", label: "Порог по умолчанию", type: "number", min: 0.01, max: 1, step: "0.01" },
  { name: "min_defect_area", label: "Мин. площадь дефекта", type: "number", min: 1, step: "1" },
  { name: "min_scratch_aspect", label: "Мин. пропорция царапины", type: "number", min: 1, step: "0.1" },
  { name: "min_diff_signal", label: "Мин. diff-сигнал", type: "number", min: 0, step: "0.1" },
  { name: "diff_percentile", label: "Перцентиль diff", type: "number", min: 50, max: 100, step: "0.1" },
  { name: "scratch_score_floor", label: "Нижний порог оценки царапины", type: "number", min: 0, max: 1, step: "0.01" },
  { name: "scratch_aspect_floor", label: "Нижний порог пропорции царапины", type: "number", min: 1, step: "0.1" },
  { name: "edge_suppress_factor", label: "Коэффициент подавления краёв", type: "number", min: 0, max: 1, step: "0.01" },
  { name: "text_min_contrast", label: "Мин. контраст текста", type: "number", min: 0, max: 255, step: "1" },
  { name: "text_structure_threshold", label: "Порог структуры текста", type: "number", min: 0, max: 255, step: "1" },
  { name: "contrast_loss_boost", label: "Усиление потери контраста", type: "number", min: 1, step: "0.1" },
  {
    name: "contrast_loss_ref_grad",
    label: "Градиент эталона при потере контраста",
    type: "number",
    min: 0,
    step: "0.1",
  },
  {
    name: "contrast_loss_cur_grad",
    label: "Градиент текущего кадра при потере контраста",
    type: "number",
    min: 0,
    step: "0.1",
  },
  { name: "enable_clahe", label: "Включить CLAHE", type: "checkbox" },
  { name: "clahe_clip_limit", label: "Предел отсечения CLAHE", type: "number", min: 0.01, step: "0.1" },
  { name: "fp_recheck_enabled", label: "Включить повторную проверку FP", type: "checkbox" },
  { name: "fp_trigger_diff_q90", label: "FP триггер diff q90", type: "number", min: 0, step: "0.1" },
];

type SettingListProps = {
  selectedCameraId: number | null;
  inspectionStats: InspectionStats;
  maxHeightPx?: number;
  onInspectionReset?: () => void;
};

type SaveFeedback = {
  state: "saving" | "success" | "error";
  text: string;
  cameraId: number | null;
};

type ResetFeedback = {
  state: "resetting" | "success" | "error";
  text: string;
};

export function SettingList({ selectedCameraId, inspectionStats, maxHeightPx, onInspectionReset }: SettingListProps) {
  const [settingData, setSettingData] = useState(INITIAL_SETTING_DATA);
  const [saveFeedback, setSaveFeedback] = useState<SaveFeedback | null>(null);
  const [resetFeedback, setResetFeedback] = useState<ResetFeedback | null>(null);
  const [isCameraSettingsOpen, setIsCameraSettingsOpen] = useState(false);
  const [isReferenceSetupOpen, setIsReferenceSetupOpen] = useState(false);
  const [isServerStreamOpen, setIsServerStreamOpen] = useState(false);
  const requestIdRef = useRef(0);

  const isBusy = settingData.status.state === "loading" || settingData.status.state === "saving";
  const canEditSettings = !isBusy;
  const brightnessScopeText = selectedCameraId === null ? "Все камеры" : `Камера ${selectedCameraId}`;
  const analysisScopeText = selectedCameraId === null ? "Все изделия камер" : `Изделие камеры ${selectedCameraId}`;
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
    setSaveFeedback(null);
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      form: updateSettingField(currentSettingData.form, fieldName, event.target.value),
    }));
  };

  const handleAnalysisFieldChange = (fieldName: AnalysisSettingFieldName) => (event: ChangeEvent<HTMLInputElement>) => {
    setSaveFeedback(null);
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
    setSaveFeedback({ state: "saving", text: "Сохранение...", cameraId: selectedCameraId });
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveSettingData(formToSave, selectedCameraId)
      .catch((error) => createSettingErrorData(error, formToSave))
      .then((nextSettingData) => {
        if (requestId === requestIdRef.current) {
          setSettingData(nextSettingData);
          setSaveFeedback(
            resolveSaveFeedback(nextSettingData.status.state, nextSettingData.status.text, selectedCameraId),
          );
        }
      });
  };

  const handleBrightnessSave = () => {
    if (!canEditSettings) {
      return;
    }

    const { form, analysisProductTypes } = settingData;
    const requestId = ++requestIdRef.current;
    setSaveFeedback({ state: "saving", text: "Сохранение...", cameraId: selectedCameraId });
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
          setSaveFeedback(
            resolveSaveFeedback(nextSettingData.status.state, nextSettingData.status.text, selectedCameraId),
          );
        }
      });
  };

  const handleLightModeChange = (constant: boolean) => {
    if (!canEditSettings) {
      return;
    }
    if (settingData.form.constantFlashMode === constant) {
      return;
    }
    const { form, analysisProductTypes } = settingData;
    const requestId = ++requestIdRef.current;
    setSaveFeedback({ state: "saving", text: "Переключение вспышек...", cameraId: selectedCameraId });
    setSettingData((current) => ({
      ...current,
      status: SAVING_SETTING_STATUS,
      form: { ...current.form, constantFlashMode: constant },
    }));
    saveLightMode(constant, form, analysisProductTypes)
      .catch((error) => ({ ...createSettingErrorData(error, form), analysisProductTypes }))
      .then((next) => {
        if (requestId === requestIdRef.current) {
          setSettingData(next);
          setSaveFeedback(resolveSaveFeedback(next.status.state, next.status.text, selectedCameraId));
        }
      });
  };

  const handleMaxShiftSave = () => {
    if (!canEditSettings) {
      return;
    }

    const { form, analysisProductTypes } = settingData;
    const requestId = ++requestIdRef.current;
    setSaveFeedback({ state: "saving", text: "Сохранение...", cameraId: selectedCameraId });
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveMaxShiftData(form, analysisProductTypes, selectedCameraId)
      .catch((error) => ({
        ...createSettingErrorData(error, form),
        analysisProductTypes,
      }))
      .then((nextSettingData) => {
        if (requestId === requestIdRef.current) {
          setSettingData(nextSettingData);
          setSaveFeedback(
            resolveSaveFeedback(nextSettingData.status.state, nextSettingData.status.text, selectedCameraId),
          );
        }
      });
  };

  const handleSavedFramesSave = () => {
    if (!canEditSettings) {
      return;
    }

    const { form, analysisProductTypes } = settingData;
    const requestId = ++requestIdRef.current;
    setSaveFeedback({ state: "saving", text: "Сохранение...", cameraId: selectedCameraId });
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveSavedFramesData(form, analysisProductTypes)
      .catch((error) => ({
        ...createSettingErrorData(error, form),
        analysisProductTypes,
      }))
      .then((nextSettingData) => {
        if (requestId === requestIdRef.current) {
          setSettingData(nextSettingData);
          setSaveFeedback(
            resolveSaveFeedback(nextSettingData.status.state, nextSettingData.status.text, selectedCameraId),
          );
        }
      });
  };

  const handleInspectionReset = () => {
    if (resetFeedback?.state === "resetting") {
      return;
    }

    setResetFeedback({ state: "resetting", text: "Сброс инспекции..." });
    orchestratorApi
      .resetInspection()
      .then((response) => {
        clearReferenceImages();
        onInspectionReset?.();
        setResetFeedback({
          state: "success",
          text: response.cleared ? "Инспекция сброшена" : "Инспекция уже была сброшена",
        });
      })
      .catch((error: unknown) => {
        setResetFeedback({ state: "error", text: errorMessage(error) });
      });
  };

  return (
    <aside
      className="setting-list"
      aria-label="Настройки"
      style={createSettingListStyle(maxHeightPx)}
    >
      <InspectionStatsPanel stats={inspectionStats} />

      <form
        className="setting-list__form"
        onSubmit={handleSubmit}
      >
        <section className="setting-list__panel">
          <h2 className="setting-list__panel-title">Управление процессом</h2>
          <div className="setting-list__quick-actions">
            <Button
              className="setting-list__quick-action setting-list__quick-action--stream"
              variant="warning"
              disabled={isBusy}
              onClick={() => setIsServerStreamOpen(true)}
            >
              <SettingActionIcon name="stream" />
              Стрим
            </Button>
            <Button
              className="setting-list__quick-action setting-list__quick-action--reference"
              disabled={isBusy}
              onClick={() => setIsReferenceSetupOpen(true)}
            >
              <SettingActionIcon name="reference" />
              Эталон
            </Button>
            <Button
              className="setting-list__quick-action setting-list__quick-action--camera"
              disabled={isBusy}
              onClick={() => setIsCameraSettingsOpen(true)}
            >
              <SettingActionIcon name="camera" />
              Камеры
            </Button>
            <Button
              className="setting-list__quick-action setting-list__quick-action--reset"
              disabled={resetFeedback?.state === "resetting"}
              onClick={handleInspectionReset}
            >
              <SettingActionIcon name="reset" />
              {resetFeedback?.state === "resetting" ? "Сброс..." : "Сброс"}
            </Button>
          </div>
          {resetFeedback && (
            <span className="setting-list__inspection-reset-status" data-status={resetFeedback.state} aria-live="polite">
              {resetFeedback.text}
            </span>
          )}
        </section>

        <section className="setting-list__panel setting-list__light-panel">
          <h2 className="setting-list__panel-title">Управление светом</h2>
          <div className="setting-list__card setting-list__brightness">
            <div className="setting-list__card-header">
              <span>Яркость света</span>
              <strong className="setting-list__scope">{brightnessScopeText}</strong>
            </div>
            <label className="setting-list__control-row">
              <span className="setting-list__visually-hidden">Уровень яркости</span>
              <SettingActionIcon
                name="light"
                className="setting-list__brightness-icon"
              />
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
            <Button
              className="setting-list__brightness-save"
              disabled={!canEditSettings}
              onClick={handleBrightnessSave}
            >
              Сохранить яркость
            </Button>
          </div>

          <section className="setting-list__card setting-list__flash-mode">
            <div className="setting-list__card-header">
              <span>Режим работы вспышек</span>
            </div>
            <div className="setting-list__flash-mode-options">
              <button
                type="button"
                data-active={!settingData.form.constantFlashMode}
                disabled={!canEditSettings}
                onClick={() => handleLightModeChange(false)}
              >
                <span aria-hidden="true">↻</span>
                По циклу
              </button>
              <button
                type="button"
                data-active={settingData.form.constantFlashMode}
                disabled={!canEditSettings}
                onClick={() => handleLightModeChange(true)}
              >
                Постоянный
              </button>
            </div>
          </section>
        </section>

        <section className="setting-list__panel">
          <h2 className="setting-list__panel-title">Параметры анализа</h2>
          {saveFeedback?.cameraId === selectedCameraId && (
            <strong
              className="setting-list__save-feedback"
              aria-live="polite"
              data-status={saveFeedback.state}
            >
              {saveFeedback.text}
            </strong>
          )}
          <details className="setting-list__collapsible-setting">
            <summary className="setting-list__section-header">
              <h3>Геометрия / стык</h3>
            </summary>
            <div className="setting-list__vertical-setting">
              <label>
                <span>Задать максимальное смещение, мм</span>
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
              <label className="setting-list__field setting-list__field--checkbox">
                <span>Сегментация стыка (параллельность по полосе)</span>
                <input
                  type="checkbox"
                  checked={settingData.form.jointSeamSegmentationEnabled}
                  disabled={!canEditSettings}
                  onChange={(event) => {
                    setSaveFeedback(null);
                    setSettingData((current) => ({
                      ...current,
                      form: {
                        ...current.form,
                        jointSeamSegmentationEnabled: event.target.checked,
                      },
                    }));
                  }}
                />
              </label>
              <Button
                className="setting-list__inline-save"
                disabled={!canEditSettings}
                onClick={handleMaxShiftSave}
              >
                Сохранить
              </Button>
            </div>
          </details>

          <details className="setting-list__collapsible-setting">
            <summary className="setting-list__section-header">
              <h3>Количество кадров</h3>
            </summary>
            <div className="setting-list__vertical-setting">
              <label>
                <span>Задать количество сохраняемых кадров</span>
                <input
                  type="number"
                  min="0"
                  max="100"
                  step="1"
                  value={settingData.form.savedFramesCount}
                  disabled={!canEditSettings}
                  onChange={handleFieldChange("savedFramesCount")}
                />
              </label>
              <Button
                className="setting-list__inline-save"
                disabled={!canEditSettings}
                onClick={handleSavedFramesSave}
              >
                Сохранить
              </Button>
            </div>
          </details>

          <details className="setting-list__collapsible-setting setting-list__analysis">
            <summary className="setting-list__section-header">
              <h3>Настройки анализа</h3>
              <strong>{analysisScopeText}</strong>
            </summary>
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
            <Button
              className="setting-list__inline-save setting-list__analysis-save"
              type="submit"
              disabled={!canEditSettings}
            >
              Сохранить
            </Button>
          </details>
        </section>
      </form>

      {isCameraSettingsOpen && (
        <CameraSettingsModal
          isOpen
          initialCameraId={selectedCameraId}
          onClose={() => setIsCameraSettingsOpen(false)}
        />
      )}
      {isReferenceSetupOpen && (
        <ReferenceSetup
          initialCameraId={selectedCameraId}
          onClose={() => setIsReferenceSetupOpen(false)}
        />
      )}
      {isServerStreamOpen && (
        <ServerStream
          isOpen
          cameraId={streamCameraId}
          onClose={() => setIsServerStreamOpen(false)}
        />
      )}
    </aside>
  );
}

function resolveSaveFeedback(
  status: "loading" | "ready" | "saving" | "error",
  errorText: string,
  cameraId: number | null,
): SaveFeedback {
  return status === "error"
    ? { state: "error", text: errorText, cameraId }
    : { state: "success", text: "Сохранено успешно", cameraId };
}

function createSettingListStyle(maxHeightPx: number | undefined): CSSProperties | undefined {
  if (!maxHeightPx || !Number.isFinite(maxHeightPx)) {
    return undefined;
  }

  const height = `${Math.round(maxHeightPx)}px`;
  return { height, maxHeight: height };
}

function SettingActionIcon({
  name,
  className,
}: {
  name: "stream" | "reference" | "camera" | "reset" | "light";
  className?: string;
}) {
  const iconClassName = ["setting-list__action-icon", className].filter(Boolean).join(" ");
  const assetIconUrl = resolveSettingActionIconUrl(name);

  if (assetIconUrl) {
    return (
      <span
        className={iconClassName}
        aria-hidden="true"
      >
        <img
          alt=""
          src={assetIconUrl}
        />
      </span>
    );
  }

  return (
    <span
      className={iconClassName}
      aria-hidden="true"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
      >
        {name === "stream" && (
          <>
            <rect
              x="4"
              y="5"
              width="16"
              height="14"
              rx="3"
            />
            <path d="M10 9.5v5l4-2.5-4-2.5Z" />
          </>
        )}
        {name === "reference" && (
          <>
            <circle
              cx="12"
              cy="12"
              r="5.5"
            />
            <circle
              cx="12"
              cy="12"
              r="1.8"
            />
            <path d="M12 3v3M12 18v3M3 12h3M18 12h3" />
          </>
        )}
        {name === "camera" && (
          <>
            <rect
              x="4"
              y="7"
              width="12"
              height="10"
              rx="2"
            />
            <path d="M16 10l4-2.2v8.4L16 14v-4Z" />
            <path d="M8 7l1-2h3l1 2" />
          </>
        )}
        {name === "reset" && (
          <>
            <path d="M7 7h10" />
            <path d="M10 7V5h4v2" />
            <path d="M9 10v7M15 10v7" />
            <path d="M6 7l1 13h10l1-13" />
          </>
        )}
        {name === "light" && (
          <>
            <circle
              cx="12"
              cy="12"
              r="3.5"
            />
            <path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6" />
          </>
        )}
      </svg>
    </span>
  );
}

function resolveSettingActionIconUrl(name: "stream" | "reference" | "camera" | "reset" | "light") {
  if (name === "stream") {
    return streamIconUrl;
  }

  if (name === "reference") {
    return benchmarkIconUrl;
  }

  if (name === "camera") {
    return camerasIconUrl;
  }

  if (name === "reset") {
    return resetIconUrl;
  }

  return undefined;
}

function InspectionStatsPanel({ stats }: { stats: InspectionStats }) {
  return (
    <section
      className="setting-list__inspection-stats"
      aria-label="Статистика инспекций"
    >
      <div className="setting-list__inspection-stats-header">
        <h2>Статистика инспекций</h2>
        <span>онлайн</span>
      </div>
      {stats.groups && stats.groups.length > 0 && (
        <div className="setting-list__inspection-groups">
          {stats.groups.map((group) => (
            <div
              key={group.id}
              className="setting-list__inspection-group"
            >
              <span className="setting-list__inspection-group-title">
                {group.label}
                <small>к. {group.cameraIds.join(", ")}</small>
              </span>
              <span>
                <small>Всего</small>
                <strong>{group.total}</strong>
              </span>
              <span>
                <small>Год</small>
                <strong>{group.passed}</strong>
              </span>
              <span>
                <small>Брак</small>
                <strong>{group.failed}</strong>
              </span>
            </div>
          ))}
        </div>
      )}
      <div className="setting-list__inspection-times">
        <div>
          <span>Старт</span>
          <strong>{formatStatsTime(stats.inspectionStartedAtMs)}</strong>
        </div>
        <div>
          <span>Эталон</span>
          <strong>
            {stats.referenceFrameId
              ? `кадр ${stats.referenceFrameId}, ${formatStatsTime(stats.referenceSetAtMs)}`
              : "не задан"}
          </strong>
        </div>
        <div>
          <span>Стоп</span>
          <strong>{formatStatsTime(stats.inspectionStoppedAtMs)}</strong>
        </div>
      </div>
    </section>
  );
}

function formatStatsTime(epochMs: number | undefined) {
  if (!epochMs || !Number.isFinite(epochMs)) {
    return "—";
  }

  return new Date(epochMs).toLocaleTimeString("ru-RU", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}
