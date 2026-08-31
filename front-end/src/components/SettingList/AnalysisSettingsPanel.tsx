import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import type { ChangeEvent, KeyboardEvent } from "react";
import { orchestratorApi } from "../../shared/api";
import type { ProAnalysisKnobs, SimpleAnalysisKnobs } from "../../shared/api";
import { subscribeAnalysisSettingsChanged } from "./analysisSettingsEvents";
import { errorMessage } from "../../shared/lib/errors";
import { Button } from "../../shared/ui/Button";

const ACCESS_CODE = "3333";
const FALLBACK_PROFILE = "reference-product";
const DEFAULT_SIMPLE: SimpleAnalysisKnobs = { threshold: 0.25, sensitivity: 0.5 };
const DEFAULT_PRO: ProAnalysisKnobs = {
  threshold: 0.25,
  // Detailed/pro strength knobs are percentages in the Python API (0–100).
  // Threshold remains a unit interval value (0–1), just like in simple mode.
  noise_tolerance: 50,
  scratch_sensitivity: 50,
  edge_suppression: 50,
  text_handling: 50,
  preprocess_strength: 50,
};

const SIMPLE_FIELDS = [
  { name: "threshold", label: "Порог брака", hint: "Ниже — чувствительнее к дефектам, выше — строже к браку." },
  { name: "sensitivity", label: "Чувствительность", hint: "Общая чувствительность алгоритма: от грубой до максимальной." },
] as const;

const PRO_FIELDS = [
  ...SIMPLE_FIELDS.slice(0, 1),
  { name: "noise_tolerance", label: "Отклик на шум", hint: "Выше — сильнее реакция на мелкие и слабые отличия." },
  { name: "scratch_sensitivity", label: "Чувствительность к царапинам", hint: "Поиск тонких и вытянутых дефектов." },
  { name: "edge_suppression", label: "Реакция на края", hint: "Насколько учитывать отличия у границ детали." },
  { name: "text_handling", label: "Работа с текстом и печатью", hint: "Чувствительность к изменениям надписей и контраста." },
  { name: "preprocess_strength", label: "Предобработка изображения", hint: "Сила выравнивания локального контраста." },
] as const;

type Props = {
  selectedCameraId: number | null;
  profile?: string;
  testFrameId?: string;
  testPinId?: string;
  onSaveComplete?: () => Promise<void> | void;
  hideSaveAction?: boolean;
};
export type AnalysisSettingsPanelHandle = {
  save: () => Promise<void>;
  getDraft: () => { mode: Mode; knobs: SimpleAnalysisKnobs | ProAnalysisKnobs };
};
type Mode = "simple" | "pro";

export const AnalysisSettingsPanel = forwardRef<AnalysisSettingsPanelHandle, Props>(function AnalysisSettingsPanel({
  selectedCameraId,
  profile = FALLBACK_PROFILE,
  testFrameId,
  testPinId,
  onSaveComplete,
  hideSaveAction = false,
}, ref) {
  const [mode, setMode] = useState<Mode>("simple");
  const [simple, setSimple] = useState(DEFAULT_SIMPLE);
  const [pro, setPro] = useState(DEFAULT_PRO);
  const [unlocked, setUnlocked] = useState(false);
  const [showUnlock, setShowUnlock] = useState(false);
  const [password, setPassword] = useState("");
  const [passwordError, setPasswordError] = useState(false);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [status, setStatus] = useState<{ kind: "loading" | "saving" | "success" | "error"; text: string }>({
    kind: "loading",
    text: "Загрузка настроек…",
  });
  const previewRequestIdRef = useRef(0);
  const previewTimerRef = useRef<number | null>(null);
  const hydratedRef = useRef(false);
  const userEditedSimpleRef = useRef(false);
  const userEditedProRef = useRef(false);

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
    userEditedSimpleRef.current = false;
    userEditedProRef.current = false;
    loadSimple(selectedCameraId, profile)
      .then((response) => {
        if (!active) return;
        setSimple(response.knobs ?? { threshold: response.settings.default_threshold, sensitivity: 0.5 });
        hydratedRef.current = true;
        setStatus({
          kind: "success",
          text: selectedCameraId === null ? "Настройки для всех камер загружены" : `Настройки камеры ${selectedCameraId} загружены`,
        });
      })
      .catch((error) => active && setStatus({ kind: "error", text: errorMessage(error) }));
    return () => { active = false; };
  }, [profile, refreshVersion, selectedCameraId]);

  useEffect(() => {
    if (!unlocked || refreshVersion === 0) return;
    let active = true;
    loadPro(selectedCameraId, profile)
      .then((response) => {
        if (!active) return;
        setPro(response.knobs ?? { ...DEFAULT_PRO, threshold: response.settings.default_threshold });
      })
      .catch((error) => active && setStatus({ kind: "error", text: errorMessage(error) }));
    return () => { active = false; };
  }, [profile, refreshVersion, selectedCameraId, unlocked]);

  useEffect(() => {
    if (hideSaveAction || !hydratedRef.current || (!userEditedSimpleRef.current && !userEditedProRef.current)) {
      return;
    }
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
    }
    const requestId = ++previewRequestIdRef.current;
    const persistSimple = mode === "simple" && userEditedSimpleRef.current;
    const persistPro = mode === "pro" && userEditedProRef.current;
    const preview = selectedCameraId !== null && Boolean(testFrameId);
    previewTimerRef.current = window.setTimeout(() => {
      setStatus({
        kind: "saving",
        text: preview ? `Сохранение и проверка на кадре ${testFrameId}…` : "Сохранение…",
      });
      void Promise.resolve()
        .then(() => (persistSimple ? saveSimple(selectedCameraId, simple) : undefined))
        .then(() => (persistPro ? savePro(selectedCameraId, pro) : undefined))
        .then(() => (preview && testPinId
          ? orchestratorApi.testAnalyzePinnedFrame(selectedCameraId, testPinId, testFrameId!)
          : undefined))
        .then(() => {
          if (requestId === previewRequestIdRef.current) {
            if (persistSimple) userEditedSimpleRef.current = false;
            if (persistPro) userEditedProRef.current = false;
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
  }, [hideSaveAction, mode, pro, selectedCameraId, simple, testFrameId, testPinId]);

  const unlock = () => {
    if (password !== ACCESS_CODE) {
      setPasswordError(true);
      return;
    }
    setUnlocked(true);
    setShowUnlock(false);
    setPassword("");
    setPasswordError(false);
    setMode("pro");
    setStatus({ kind: "loading", text: "Загрузка расширенных настроек…" });
    loadPro(selectedCameraId, profile)
      .then((response) => {
        setPro(response.knobs ?? { ...DEFAULT_PRO, threshold: response.settings.default_threshold });
        setStatus({ kind: "success", text: "Расширенные настройки загружены" });
      })
      .catch((error) => setStatus({ kind: "error", text: errorMessage(error) }));
  };

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
      if (mode === "simple") {
        const response = await saveSimple(selectedCameraId, simple);
        if (response?.knobs) setSimple(response.knobs);
        userEditedSimpleRef.current = false;
      } else {
        const response = await savePro(selectedCameraId, pro);
        if (response?.knobs) setPro(response.knobs);
        userEditedProRef.current = false;
      }
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
    getDraft: () => ({ mode, knobs: mode === "simple" ? simple : pro }),
  }));

  const openPro = () => {
    if (unlocked) {
      setMode("pro");
    } else {
      setShowUnlock(true);
      setPasswordError(false);
    }
  };

  const lockPro = () => {
    setUnlocked(false);
    setMode("simple");
    setPassword("");
    setPasswordError(false);
    setShowUnlock(false);
    setStatus({ kind: "success", text: "Расширенные настройки заблокированы" });
  };

  const busy = status.kind === "loading" || status.kind === "saving";
  const fields = mode === "simple" ? SIMPLE_FIELDS : PRO_FIELDS;
  const values = mode === "simple" ? simple : pro;

  return (
    <div className="analysis-presets">
      <div className="analysis-presets__tabs" role="tablist" aria-label="Режим настроек анализа">
        <button type="button" className={mode === "simple" ? "is-active" : ""} onClick={() => setMode("simple")}>Быстрые</button>
        <button type="button" className={mode === "pro" ? "is-active" : ""} onClick={openPro}>Расширенные {!unlocked && <span>🔒</span>}</button>
      </div>

      <p className="analysis-presets__intro">
        <span>
          {mode === "simple" ? "Основные параметры для быстрой калибровки." : "Точная настройка отдельных групп алгоритма."}
        </span>
        {unlocked && (
          <button type="button" className="analysis-presets__lock-button" onClick={lockPro}>
            🔒 Заблокировать
          </button>
        )}
      </p>

      <div className="analysis-presets__fields">
        {fields.map((field) => {
          const value = values[field.name as keyof typeof values];
          // Simple threshold/sensitivity are stored as 0–1, while detailed
          // strength knobs returned by /pro are stored as 0–100 percentages.
          const isProStrength = mode === "pro" && field.name !== "threshold";
          const valueScale = isProStrength ? 1 : 100;
          const minValue = isProStrength ? 0 : field.name === "threshold" ? 0.01 : 0;
          const maxValue = isProStrength ? 100 : 1;
          const sliderStep = isProStrength ? 0.1 : 0.001;
          const minimumPercent = minValue * valueScale;
          const maximumPercent = maxValue * valueScale;
          const updateValue = (next: number) => {
            if (mode === "simple") {
              userEditedSimpleRef.current = true;
              setSimple((current) => ({ ...current, [field.name]: next }));
            } else {
              userEditedProRef.current = true;
              setPro((current) => ({ ...current, [field.name]: next }));
            }
          };
          const updatePercent = (percent: number) => {
            updateValue(
              Math.min(maximumPercent, Math.max(minimumPercent, percent)) / valueScale,
            );
          };
          return (
            <label className="analysis-presets__field" key={field.name}>
              <span>
                <strong>{field.label}</strong>
                <span className="analysis-presets__percent-input">
                  <input
                    aria-label={`${field.label}, проценты`}
                    type="number"
                    min={minimumPercent}
                    max="100"
                    step="0.1"
                    inputMode="decimal"
                    value={(Number(value) * valueScale).toFixed(1)}
                    disabled={busy}
                    onChange={(event: ChangeEvent<HTMLInputElement>) => {
                      const percent = event.target.valueAsNumber;
                      if (!Number.isFinite(percent)) return;
                      updatePercent(percent);
                    }}
                  />
                  <span aria-hidden="true">%</span>
                  <span className="analysis-presets__percent-steppers">
                    <button
                      type="button"
                      aria-label={`Увеличить ${field.label} на одну десятую процента`}
                      disabled={busy || Number(value) >= maxValue}
                      onClick={() => updatePercent(Number(value) * valueScale + 0.1)}
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      aria-label={`Уменьшить ${field.label} на одну десятую процента`}
                      disabled={busy || Number(value) * valueScale <= minimumPercent}
                      onClick={() => updatePercent(Number(value) * valueScale - 0.1)}
                    >
                      ▼
                    </button>
                  </span>
                </span>
              </span>
              <input
                type="range" min={minValue} max={maxValue} step={sliderStep}
                value={value} disabled={busy}
                onChange={(event: ChangeEvent<HTMLInputElement>) => updateValue(Number(event.target.value))}
              />
              <small>{field.hint}</small>
            </label>
          );
        })}
      </div>

      <div className="analysis-presets__footer">
        <span data-kind={status.kind} aria-live="polite">{status.text}</span>
        {!hideSaveAction && <Button type="button" disabled={busy} onClick={() => void persist()}>Сохранить</Button>}
      </div>

      {showUnlock && (
        <div className="analysis-presets__unlock" role="dialog" aria-modal="true" aria-labelledby="analysis-unlock-title">
          <div className="analysis-presets__unlock-card">
            <button type="button" className="analysis-presets__unlock-close" aria-label="Закрыть" onClick={() => setShowUnlock(false)}>×</button>
            <span className="analysis-presets__lock">🔒</span>
            <h4 id="analysis-unlock-title">Расширенные настройки</h4>
            <p>Введите пароль для доступа к полному набору параметров.</p>
            <input
              autoFocus type="password" inputMode="numeric" value={password} placeholder="Пароль"
              aria-invalid={passwordError}
              onChange={(event) => { setPassword(event.target.value); setPasswordError(false); }}
              onKeyDown={(event: KeyboardEvent<HTMLInputElement>) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  unlock();
                }
              }}
            />
            {passwordError && <span className="analysis-presets__password-error">Неверный пароль</span>}
            <Button type="button" onClick={unlock}>Открыть настройки</Button>
          </div>
        </div>
      )}
    </div>
  );
});

function loadSimple(cameraId: number | null, profile: string) {
  return cameraId === null ? loadAllCamerasSimple(profile) : orchestratorApi.getCameraSimpleAnalysisSettings(cameraId);
}
function saveSimple(cameraId: number | null, knobs: SimpleAnalysisKnobs) {
  return cameraId === null ? saveAllCamerasSimple(knobs) : orchestratorApi.setCameraSimpleAnalysisSettings(cameraId, knobs);
}
function loadPro(cameraId: number | null, profile: string) {
  return cameraId === null ? loadAllCamerasPro(profile) : orchestratorApi.getCameraProAnalysisSettings(cameraId);
}
function savePro(cameraId: number | null, knobs: ProAnalysisKnobs) {
  return cameraId === null ? saveAllCamerasPro(knobs) : orchestratorApi.setCameraProAnalysisSettings(cameraId, knobs);
}

async function loadAllCamerasSimple(fallbackProfile: string) {
  const cameras = (await orchestratorApi.listCameras()).cameras;
  return cameras.length > 0
    ? orchestratorApi.getCameraSimpleAnalysisSettings(cameras[0])
    : orchestratorApi.getSimpleAnalysisSettings(fallbackProfile);
}

async function loadAllCamerasPro(fallbackProfile: string) {
  const cameras = (await orchestratorApi.listCameras()).cameras;
  return cameras.length > 0
    ? orchestratorApi.getCameraProAnalysisSettings(cameras[0])
    : orchestratorApi.getProAnalysisSettings(fallbackProfile);
}

async function saveAllCamerasSimple(knobs: SimpleAnalysisKnobs) {
  const cameras = (await orchestratorApi.listCameras()).cameras;
  if (cameras.length === 0) {
    throw new Error("Список камер пуст — настройки не сохранены");
  }
  const responses = await Promise.all(
    cameras.map((cameraId) => orchestratorApi.setCameraSimpleAnalysisSettings(cameraId, knobs)),
  );
  return responses[0];
}

async function saveAllCamerasPro(knobs: ProAnalysisKnobs) {
  const cameras = (await orchestratorApi.listCameras()).cameras;
  if (cameras.length === 0) {
    throw new Error("Список камер пуст — настройки не сохранены");
  }
  const responses = await Promise.all(
    cameras.map((cameraId) => orchestratorApi.setCameraProAnalysisSettings(cameraId, knobs)),
  );
  return responses[0];
}
