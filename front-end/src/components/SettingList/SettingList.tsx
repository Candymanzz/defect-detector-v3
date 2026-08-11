import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ChangeEvent, CSSProperties, FocusEvent, FormEvent, MouseEvent } from "react";
import { createPortal } from "react-dom";
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
import { AnalysisSettingsPanel } from "./AnalysisSettingsPanel";
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
  hint: string;
  type: "number" | "checkbox";
  min?: number;
  max?: number;
  step?: string;
}> = [
  {
    name: "default_threshold",
    label: "Порог брака",
    hint: "Граница между «Годен» и «Брак». Выше — меньше ложного брака, но слабый дефект легче пропустить. Ниже — выше чувствительность.",
    type: "number",
    min: 0.01,
    max: 1,
    step: "0.01",
  },
  {
    name: "min_defect_area",
    label: "Минимальный размер дефекта",
    hint: "Пятна меньше указанной площади в пикселях игнорируются. Увеличьте при шуме и пыли; уменьшите для поиска мелких точечных дефектов.",
    type: "number",
    min: 1,
    step: "1",
  },
  {
    name: "min_scratch_aspect",
    label: "Минимальная вытянутость царапины",
    hint: "Требуемое отношение длины пятна к ширине. Выше — учитываются только длинные тонкие линии; ниже — больше объектов считается царапинами.",
    type: "number",
    min: 1,
    step: "0.1",
  },
  {
    name: "min_diff_signal",
    label: "Минимальная сила отличия",
    hint: "Слабые отличия кадра от эталона ниже этого значения игнорируются. Увеличьте при шуме освещения; уменьшите, если не видны слабые дефекты.",
    type: "number",
    min: 0,
    step: "0.1",
  },
  {
    name: "diff_percentile",
    label: "Отсечение слабых отличий",
    hint: "Определяет, какая часть самых сильных отличий попадёт в маску. Выше — маска чище и меньше; ниже — шире и чувствительнее, но шумнее.",
    type: "number",
    min: 50,
    max: 100,
    step: "0.1",
  },
  {
    name: "scratch_score_floor",
    label: "Минимальная оценка царапины",
    hint: "Нижняя граница оценки для найденной вытянутой царапины. Увеличьте, если видимая царапина остаётся «Годен»; уменьшите при ложных полосках.",
    type: "number",
    min: 0,
    max: 1,
    step: "0.01",
  },
  {
    name: "scratch_aspect_floor",
    label: "Вытянутость для усиления царапины",
    hint: "Начиная с какой вытянутости применяется минимальная оценка царапины. Выше — только для очень длинных линий; ниже — срабатывает чаще.",
    type: "number",
    min: 1,
    step: "0.1",
  },
  {
    name: "edge_suppress_factor",
    label: "Чувствительность к краям детали",
    hint: "0 почти полностью подавляет отличия на кромках, 1 учитывает их полностью. Уменьшите при ложном браке из-за дрожания контура.",
    type: "number",
    min: 0,
    max: 1,
    step: "0.01",
  },
  {
    name: "text_min_contrast",
    label: "Порог изменений на тексте",
    hint: "Слабые отличия в областях текста ниже порога игнорируются. Увеличьте при ложных срабатываниях на буквах и логотипах.",
    type: "number",
    min: 0,
    max: 255,
    step: "1",
  },
  {
    name: "text_structure_threshold",
    label: "Распознавание текстовых областей",
    hint: "Определяет, какие области считаются текстом. Ниже — текстовыми считается больше областей; выше — текстовые правила применяются реже.",
    type: "number",
    min: 0,
    max: 255,
    step: "1",
  },
  {
    name: "contrast_loss_boost",
    label: "Чувствительность к стёртому тексту",
    hint: "Усиливает отличие там, где контраст эталона ослаб на текущем кадре. Увеличьте для поиска стёртой печати; уменьшите при реакции на лёгкое выцветание.",
    type: "number",
    min: 1,
    step: "0.1",
  },
  {
    name: "contrast_loss_ref_grad",
    label: "Минимальная резкость текста на эталоне",
    hint: "Порог заметного края или текста на эталоне. Выше — потеря контраста ищется только в изначально резких местах; ниже — также в слабых текстурах.",
    type: "number",
    min: 0,
    step: "0.1",
  },
  {
    name: "contrast_loss_cur_grad",
    label: "Порог ослабления текста на кадре",
    hint: "Определяет, насколько слабым должен стать край на текущем кадре. Выше — меньше ложных срабатываний; ниже — чувствительнее к стиранию.",
    type: "number",
    min: 0,
    step: "0.1",
  },
  {
    name: "enable_clahe",
    label: "Выравнивать локальный контраст",
    hint: "Помогает при неравномерном освещении. На гладких или блестящих поверхностях может усилить текстуру и шум.",
    type: "checkbox",
  },
  {
    name: "clahe_clip_limit",
    label: "Сила выравнивания контраста",
    hint: "Работает только при включённом выравнивании. Выше — сильнее проявляются слабые отличия и шум; ниже — обработка мягче.",
    type: "number",
    min: 0.01,
    step: "0.1",
  },
  {
    name: "fp_recheck_enabled",
    label: "Учитывать исключающие зоны",
    hint: "Включает повторный расчёт с подавлением заранее размеченных зон ложных срабатываний. Обычно рекомендуется оставить включённым.",
    type: "checkbox",
  },
  {
    name: "fp_trigger_diff_q90",
    label: "Порог активации исключающей зоны",
    hint: "Насколько сильным должно быть отличие внутри зоны, чтобы применилось подавление. Ниже — зона срабатывает чаще; выше — реже.",
    type: "number",
    min: 0,
    step: "0.1",
  },
];

// Legacy full-field metadata is kept for compatibility while the UI uses Simple/Pro presets.
void ANALYSIS_SETTING_FIELDS;

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

type AnalysisTooltip = {
  text: string;
  left: number;
  top: number;
  placement: "above" | "below";
};

export function SettingList({ selectedCameraId, inspectionStats, maxHeightPx, onInspectionReset }: SettingListProps) {
  const [settingData, setSettingData] = useState(INITIAL_SETTING_DATA);
  const [saveFeedback, setSaveFeedback] = useState<SaveFeedback | null>(null);
  const [resetFeedback, setResetFeedback] = useState<ResetFeedback | null>(null);
  const [stopFeedback, setStopFeedback] = useState<ResetFeedback | null>(null);
  const [inspectionRunning, setInspectionRunning] = useState<boolean | null>(null);
  const [analysisTooltip, setAnalysisTooltip] = useState<AnalysisTooltip | null>(null);
  const [isCameraSettingsOpen, setIsCameraSettingsOpen] = useState(false);
  const [isReferenceSetupOpen, setIsReferenceSetupOpen] = useState(false);
  const [isServerStreamOpen, setIsServerStreamOpen] = useState(false);
  const requestIdRef = useRef(0);

  const isBusy = settingData.status.state === "loading" || settingData.status.state === "saving";
  const canEditSettings = !isBusy;
  const brightnessScopeText = selectedCameraId === null ? "Все камеры" : `Камера ${selectedCameraId}`;
  const analysisScopeText = selectedCameraId === null ? "Все изделия камер" : `Изделие камеры ${selectedCameraId}`;
  const streamCameraId = selectedCameraId ?? SETTINGS_STREAM_CAMERA_ID;

  useEffect(() => {
    let active = true;
    const refreshInspectionState = () => {
      void orchestratorApi
        .getInspectionStatus()
        .then((response) => {
          if (active) setInspectionRunning(response.enabledCameraIds.length > 0);
        })
        .catch(() => undefined);
    };
    refreshInspectionState();
    const timer = window.setInterval(refreshInspectionState, 1500);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  const showAnalysisTooltip = (event: MouseEvent<HTMLSpanElement> | FocusEvent<HTMLSpanElement>, text: string) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const tooltipWidth = Math.min(300, window.innerWidth - 24);
    const placement = rect.top > window.innerHeight / 2 ? "above" : "below";
    setAnalysisTooltip({
      text,
      left: Math.min(
        window.innerWidth - tooltipWidth - 12,
        Math.max(12, rect.left + rect.width / 2 - tooltipWidth / 2),
      ),
      top: placement === "above" ? rect.top - 8 : rect.bottom + 8,
      placement,
    });
  };
  void showAnalysisTooltip;

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
  void handleAnalysisFieldChange;

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

  const handleInspectionStop = () => {
    if (stopFeedback?.state === "resetting") {
      return;
    }

    const shouldStart = inspectionRunning === false;
    setStopFeedback({
      state: "resetting",
      text: shouldStart ? "Запуск инспекции со следующей группы..." : "Остановка инспекции...",
    });
    const request = shouldStart ? orchestratorApi.startAllInspections() : orchestratorApi.stopAllInspections();
    request
      .then((response) => {
        const isRunning = response.enabledCameraIds.length > 0;
        setInspectionRunning(isRunning);
        window.dispatchEvent(new CustomEvent("inspection-control-changed", { detail: response }));
        setStopFeedback({
          state: "success",
          text: isRunning
            ? "Инспекция запущена — ожидание следующей группы кадров"
            : "Инспекция остановлена; отображается только поток кадров",
        });
      })
      .catch((error: unknown) => {
        setStopFeedback({ state: "error", text: errorMessage(error) });
      });
  };

  return (
    <aside
      className="setting-list"
      aria-label="Настройки"
      style={createSettingListStyle(maxHeightPx)}
      onScrollCapture={() => setAnalysisTooltip(null)}
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
              className={`setting-list__quick-action ${inspectionRunning === false ? "setting-list__quick-action--start" : "setting-list__quick-action--reset"}`}
              disabled={inspectionRunning === null || stopFeedback?.state === "resetting"}
              onClick={handleInspectionStop}
            >
              <SettingActionIcon name={inspectionRunning === false ? "start" : "stop"} />
              {stopFeedback?.state === "resetting"
                ? inspectionRunning === false
                  ? "Пуск..."
                  : "Стоп..."
                : inspectionRunning === false
                  ? "Пуск инспекции"
                  : "Стоп инспекции"}
            </Button>
          </div>
          {stopFeedback && (
            <span
              className="setting-list__inspection-reset-status"
              data-status={stopFeedback.state}
              aria-live="polite"
            >
              {stopFeedback.text}
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
              <label>
                <span>Чувствительность сегментации стыка</span>
                <input
                  type="number"
                  min="0"
                  max="1"
                  step="0.05"
                  value={settingData.form.jointSeamSegmentationSensitivity}
                  disabled={!canEditSettings}
                  onChange={handleFieldChange("jointSeamSegmentationSensitivity")}
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
            <AnalysisSettingsPanel
              selectedCameraId={selectedCameraId}
              profile={settingData.analysisProductTypes[0]}
            />
          </details>
        </section>

        <div className="setting-list__inspection-reset-block">
          <Button
            className="setting-list__inspection-reset"
            disabled={resetFeedback?.state === "resetting"}
            onClick={handleInspectionReset}
          >
            {resetFeedback?.state === "resetting" ? "Сброс инспекции..." : "Сброс инспекции"}
          </Button>
          {resetFeedback && (
            <span
              className="setting-list__inspection-reset-status"
              data-status={resetFeedback.state}
              aria-live="polite"
            >
              {resetFeedback.text}
            </span>
          )}
        </div>
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
      {analysisTooltip &&
        createPortal(
          <div
            className="setting-list__analysis-tooltip"
            data-placement={analysisTooltip.placement}
            role="tooltip"
            style={{ left: analysisTooltip.left, top: analysisTooltip.top }}
          >
            {analysisTooltip.text}
          </div>,
          document.body,
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
  name: "stream" | "reference" | "camera" | "reset" | "start" | "stop" | "light";
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
        {name === "stop" && <rect x="6" y="6" width="12" height="12" rx="2" />}
        {name === "start" && <path d="M8 5.5v13l10-6.5L8 5.5Z" />}
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

function resolveSettingActionIconUrl(name: "stream" | "reference" | "camera" | "reset" | "start" | "stop" | "light") {
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
