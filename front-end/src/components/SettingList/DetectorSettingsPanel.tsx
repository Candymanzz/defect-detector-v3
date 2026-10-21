import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import { orchestratorApi } from "../../shared/api";
import type { DetectorSettings, SeamSettings } from "../../shared/api";
import { subscribeAnalysisSettingsChanged } from "./analysisSettingsEvents";
import { errorMessage } from "../../shared/lib/errors";
import { Button } from "../../shared/ui/Button";
import { SeamSettingsSection } from "./SeamSettingsSection";
import "./SeamSettingsSection.css";

/**
 * Настройки анализа поверхности (cppAnalisSurface): пять реальных параметров детектора.
 * Хранятся на сервисе по профилю камеры; здесь же — временные настройки для проверки тестового кадра.
 */

const DEFAULT_SEAM: SeamSettings = {
  enabled: false,
  p0: [0, 0],
  p1: [0, 0],
  band: 30,
  tol_mid: 1,
  tol_edge: 5,
  edge_frac: 0.12,
};

const DEFAULT_SETTINGS: DetectorSettings = {
  sensitivity: 0.5,
  threshold: 0.25,
  shift_tolerance: 3,
  min_blob_area: 20,
  inspect_scale: 1,
  seam: DEFAULT_SEAM,
};

type FieldName = Exclude<keyof DetectorSettings, "seam">;

type Field = {
  name: FieldName;
  label: string;
  hint: string;
  min: number;
  max: number;
  /** шаг ползунка и стрелок */
  step: number;
  /** значение в интерфейсе = значение настройки * factor */
  factor: number;
  unit: string;
  decimals: number;
};

const FIELDS: readonly Field[] = [
  {
    name: "sensitivity",
    label: "Чувствительность",
    hint: "Насколько мелкие отличия от эталона считаются дефектом. Выше — строже, но растёт риск ложных срабатываний от света и шума.",
    min: 0, max: 1, step: 0.01, factor: 100, unit: "%", decimals: 0,
  },
  {
    name: "threshold",
    label: "Порог брака",
    hint: "Кадр бракуется, когда оценка аномалии достигает порога. Оценка = доля площади ROI с отличиями, 100% соответствует 5% площади. Ниже порог — строже к браку.",
    min: 0.01, max: 1, step: 0.005, factor: 100, unit: "%", decimals: 1,
  },
  {
    name: "shift_tolerance",
    label: "Допуск на сдвиг",
    hint: "Допустимое смещение кадра относительно эталона в пикселях исходного кадра. Больше — меньше ложных зон по краям при дрожании изделия.",
    min: 0, max: 15, step: 1, factor: 1, unit: "px", decimals: 0,
  },
  {
    name: "min_blob_area",
    label: "Минимальная площадь дефекта",
    hint: "Отличия меньше этой площади (в пикселях исходного кадра) считаются шумом и не учитываются.",
    min: 1, max: 500, step: 1, factor: 1, unit: "px", decimals: 0,
  },
  {
    name: "inspect_scale",
    label: "Масштаб анализа",
    hint: "Во сколько раз кадр уменьшается перед анализом. Меньше — быстрее, но мелкие дефекты можно потерять. Влияет на скорость пачки из 10 кадров.",
    min: 0.2, max: 1, step: 0.05, factor: 100, unit: "%", decimals: 0,
  },
];

type Props = {
  selectedCameraId: number | null;
  profile?: string;
  testFrameId?: string;
  testPinId?: string;
  onSaveComplete?: () => Promise<void> | void;
  hideSaveAction?: boolean;
};

export type DetectorSettingsPanelHandle = {
  save: () => Promise<void>;
  getDraft: () => { settings: Partial<DetectorSettings> };
};

export const DetectorSettingsPanel = forwardRef<DetectorSettingsPanelHandle, Props>(function DetectorSettingsPanel({
  selectedCameraId,
  testFrameId,
  testPinId,
  onSaveComplete,
  hideSaveAction = false,
}, ref) {
  const [settings, setSettings] = useState<DetectorSettings>(DEFAULT_SETTINGS);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [status, setStatus] = useState<{ kind: "loading" | "saving" | "success" | "error"; text: string }>({
    kind: "loading",
    text: "Загрузка настроек…",
  });
  const previewTimerRef = useRef<number | null>(null);
  const previewRequestIdRef = useRef(0);
  const hydratedRef = useRef(false);
  const userEditedRef = useRef(false);

  useEffect(() => {
    return subscribeAnalysisSettingsChanged((changedCameraId) => {
      if (selectedCameraId === null || changedCameraId === null || changedCameraId === selectedCameraId) {
        setRefreshVersion((version) => version + 1);
      }
    });
  }, [selectedCameraId]);

  useEffect(() => {
    let active = true;
    hydratedRef.current = false;
    userEditedRef.current = false;
    loadSettings(selectedCameraId)
      .then((response) => {
        if (!active) return;
        setSettings({ ...DEFAULT_SETTINGS, ...response.settings });
        hydratedRef.current = true;
        setStatus({
          kind: "success",
          text: selectedCameraId === null ? "Настройки для всех камер загружены" : `Настройки камеры ${selectedCameraId} загружены`,
        });
      })
      .catch((error) => active && setStatus({ kind: "error", text: errorMessage(error) }));
    return () => { active = false; };
  }, [refreshVersion, selectedCameraId]);

  // Автосохранение: правка полей сразу уходит в сервис; в окне проверки кадра он ещё и пересчитывается.
  useEffect(() => {
    if (hideSaveAction || !hydratedRef.current || !userEditedRef.current) {
      return;
    }
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
    }
    const requestId = ++previewRequestIdRef.current;
    const preview = selectedCameraId !== null && Boolean(testFrameId);
    previewTimerRef.current = window.setTimeout(() => {
      setStatus({
        kind: "saving",
        text: preview ? `Сохранение и проверка на кадре ${testFrameId}…` : "Сохранение…",
      });
      void Promise.resolve()
        .then(() => saveSettings(selectedCameraId, settings))
        .then(() => (preview && testPinId
          ? orchestratorApi.testAnalyzePinnedFrame(selectedCameraId, testPinId, testFrameId!)
          : undefined))
        .then(() => {
          if (requestId === previewRequestIdRef.current) {
            userEditedRef.current = false;
            setStatus({
              kind: "success",
              text: preview
                ? `Настройки сохранены, кадр ${testFrameId} пересчитан`
                : selectedCameraId === null
                  ? "Настройки сохранены для всех камер"
                  : `Настройки камеры ${selectedCameraId} сохранены`,
            });
          }
        })
        .catch((error) => {
          if (requestId === previewRequestIdRef.current) {
            setStatus({ kind: "error", text: errorMessage(error) });
          }
        });
    }, 450);
    return () => {
      if (previewTimerRef.current !== null) {
        window.clearTimeout(previewTimerRef.current);
        previewTimerRef.current = null;
      }
    };
  }, [hideSaveAction, selectedCameraId, settings, testFrameId, testPinId]);

  const persist = async () => {
    if (!hydratedRef.current) {
      const error = new Error("Настройки анализа ещё загружаются");
      setStatus({ kind: "error", text: error.message });
      throw error;
    }
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
      previewTimerRef.current = null;
    }
    previewRequestIdRef.current += 1;
    setStatus({ kind: "saving", text: "Сохранение…" });
    try {
      const saved = await saveSettings(selectedCameraId, settings);
      if (saved) {
        setSettings({ ...DEFAULT_SETTINGS, ...saved.settings });
      }
      userEditedRef.current = false;
      if (!hideSaveAction) {
        if (selectedCameraId !== null && testFrameId && testPinId) {
          await orchestratorApi.testAnalyzePinnedFrame(selectedCameraId, testPinId, testFrameId);
          setStatus({ kind: "success", text: `Сохранено, кадр ${testFrameId} пересчитан` });
          return;
        }
        await onSaveComplete?.();
      }
      setStatus({
        kind: "success",
        text: selectedCameraId === null ? "Настройки применены ко всем камерам" : `Настройки применены к камере ${selectedCameraId}`,
      });
    } catch (error) {
      setStatus({ kind: "error", text: errorMessage(error) });
      throw error;
    }
  };

  useImperativeHandle(ref, () => ({
    save: persist,
    // линия стыка принадлежит конкретной камере: в режиме «все камеры» она в настройки не попадает
    getDraft: () => ({ settings: selectedCameraId === null ? withoutSeam(settings) : settings }),
  }));

  const reset = () => {
    setStatus({ kind: "saving", text: "Сброс к значениям по умолчанию…" });
    resetSettings(selectedCameraId)
      .then((response) => {
        setSettings({ ...DEFAULT_SETTINGS, ...(response?.settings ?? {}) });
        userEditedRef.current = false;
        setStatus({ kind: "success", text: "Настройки сброшены к значениям по умолчанию" });
      })
      .catch((error) => setStatus({ kind: "error", text: errorMessage(error) }));
  };

  const busy = status.kind === "loading" || status.kind === "saving";

  return (
    <div className="analysis-presets">
      <p className="analysis-presets__intro">
        <span>Параметры сравнения кадра с эталоном. ROI (область контроля) задаётся отдельно на эталоне камеры.</span>
        <button type="button" className="analysis-presets__lock-button" disabled={busy} onClick={reset}>
          Сбросить
        </button>
      </p>

      <div className="analysis-presets__fields">
        {FIELDS.map((field) => {
          const value = settings[field.name];
          const shown = value * field.factor;
          const clampValue = (next: number) => Math.min(field.max, Math.max(field.min, next));
          const updateValue = (next: number) => {
            userEditedRef.current = true;
            setSettings((current) => ({ ...current, [field.name]: clampValue(next) }));
          };
          const stepShown = field.step * field.factor;
          return (
            <label className="analysis-presets__field" key={field.name}>
              <span>
                <strong>{field.label}</strong>
                <span className="analysis-presets__percent-input">
                  <input
                    aria-label={`${field.label}, ${field.unit}`}
                    type="number"
                    min={field.min * field.factor}
                    max={field.max * field.factor}
                    step={stepShown}
                    inputMode="decimal"
                    value={shown.toFixed(field.decimals)}
                    disabled={busy}
                    onChange={(event: ChangeEvent<HTMLInputElement>) => {
                      const typed = event.target.valueAsNumber;
                      if (!Number.isFinite(typed)) return;
                      updateValue(typed / field.factor);
                    }}
                  />
                  <span aria-hidden="true">{field.unit}</span>
                  <span className="analysis-presets__percent-steppers">
                    <button
                      type="button"
                      aria-label={`Увеличить: ${field.label}`}
                      disabled={busy || value >= field.max}
                      onClick={() => updateValue(value + field.step)}
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      aria-label={`Уменьшить: ${field.label}`}
                      disabled={busy || value <= field.min}
                      onClick={() => updateValue(value - field.step)}
                    >
                      ▼
                    </button>
                  </span>
                </span>
              </span>
              <input
                type="range"
                min={field.min}
                max={field.max}
                step={field.step}
                value={value}
                disabled={busy}
                onChange={(event: ChangeEvent<HTMLInputElement>) => updateValue(Number(event.target.value))}
              />
              <small>{field.hint}</small>
            </label>
          );
        })}
      </div>

      {selectedCameraId !== null && (
        <SeamSettingsSection
          cameraId={selectedCameraId}
          seam={settings.seam}
          disabled={busy}
          onChange={(seam) => {
            userEditedRef.current = true;
            setSettings((current) => ({ ...current, seam }));
          }}
        />
      )}

      <div className="analysis-presets__footer">
        <span data-kind={status.kind} aria-live="polite">{status.text}</span>
        {!hideSaveAction && <Button type="button" disabled={busy} onClick={() => void persist()}>Сохранить</Button>}
      </div>
    </div>
  );
});

async function allCameras() {
  const cameras = (await orchestratorApi.listCameras()).cameras;
  if (cameras.length === 0) {
    throw new Error("Список камер пуст — настройки не сохранены");
  }
  return cameras;
}

async function loadSettings(cameraId: number | null) {
  if (cameraId !== null) return orchestratorApi.getCameraDetectorSettings(cameraId);
  const cameras = (await orchestratorApi.listCameras()).cameras;
  return cameras.length > 0
    ? orchestratorApi.getCameraDetectorSettings(cameras[0])
    : orchestratorApi.getDefaultDetectorSettings();
}

function withoutSeam(settings: DetectorSettings): Partial<DetectorSettings> {
  const rest: Partial<DetectorSettings> = { ...settings };
  delete rest.seam;
  return rest;
}

async function saveSettings(cameraId: number | null, settings: DetectorSettings) {
  if (cameraId !== null) return orchestratorApi.setCameraDetectorSettings(cameraId, settings);
  // стык задаётся отдельно для каждой камеры — общее сохранение его не трогает
  const common = withoutSeam(settings);
  const responses = await Promise.all((await allCameras()).map((id) => orchestratorApi.setCameraDetectorSettings(id, common)));
  return responses[0];
}

async function resetSettings(cameraId: number | null) {
  if (cameraId !== null) return orchestratorApi.resetCameraDetectorSettings(cameraId);
  const responses = await Promise.all((await allCameras()).map((id) => orchestratorApi.resetCameraDetectorSettings(id)));
  return responses[0];
}
