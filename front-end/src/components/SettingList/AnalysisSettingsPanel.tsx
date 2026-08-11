import { useEffect, useRef, useState } from "react";
import type { ChangeEvent, KeyboardEvent } from "react";
import { orchestratorApi } from "../../shared/api";
import type { ProAnalysisKnobs, SimpleAnalysisKnobs } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { Button } from "../../shared/ui/Button";

const ACCESS_CODE = "3333";
const FALLBACK_PROFILE = "reference-product";
const DEFAULT_SIMPLE: SimpleAnalysisKnobs = { threshold: 0.25, sensitivity: 0.5 };
const DEFAULT_PRO: ProAnalysisKnobs = {
  threshold: 0.25,
  noise_tolerance: 0.5,
  scratch_sensitivity: 0.5,
  edge_suppression: 0.5,
  text_handling: 0.5,
  preprocess_strength: 0.5,
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
  onSaveComplete?: () => Promise<void> | void;
};
type Mode = "simple" | "pro";

export function AnalysisSettingsPanel({
  selectedCameraId,
  profile = FALLBACK_PROFILE,
  testFrameId,
  onSaveComplete,
}: Props) {
  const [mode, setMode] = useState<Mode>("simple");
  const [simple, setSimple] = useState(DEFAULT_SIMPLE);
  const [pro, setPro] = useState(DEFAULT_PRO);
  const [unlocked, setUnlocked] = useState(false);
  const [showUnlock, setShowUnlock] = useState(false);
  const [password, setPassword] = useState("");
  const [passwordError, setPasswordError] = useState(false);
  const [status, setStatus] = useState<{ kind: "loading" | "saving" | "success" | "error"; text: string }>({
    kind: "loading",
    text: "Загрузка настроек…",
  });
  const previewRequestIdRef = useRef(0);
  const previewTimerRef = useRef<number | null>(null);

  useEffect(() => {
    let active = true;
    loadSimple(selectedCameraId, profile)
      .then((response) => {
        if (!active) return;
        setSimple(response.knobs ?? { threshold: response.settings.default_threshold, sensitivity: 0.5 });
        setStatus({ kind: "success", text: "Настройки загружены" });
      })
      .catch((error) => active && setStatus({ kind: "error", text: errorMessage(error) }));
    return () => { active = false; };
  }, [selectedCameraId, profile]);

  useEffect(() => {
    if (selectedCameraId === null || !testFrameId) {
      return;
    }
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
    }
    const requestId = ++previewRequestIdRef.current;
    previewTimerRef.current = window.setTimeout(() => {
      const persistRequest = mode === "simple"
        ? saveSimple(selectedCameraId, profile, simple)
        : savePro(selectedCameraId, profile, pro);
      setStatus({ kind: "saving", text: `Проверка на кадре ${testFrameId}…` });
      void persistRequest
        .then(() => orchestratorApi.testAnalyzeArchiveFrame(selectedCameraId, testFrameId))
        .then(() => {
          if (requestId === previewRequestIdRef.current) {
            setStatus({ kind: "success", text: `Кадр ${testFrameId} отправлен на повторный анализ` });
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
  }, [mode, pro, profile, selectedCameraId, simple, testFrameId]);

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

  const save = () => {
    if (previewTimerRef.current !== null) {
      window.clearTimeout(previewTimerRef.current);
      previewTimerRef.current = null;
    }
    previewRequestIdRef.current += 1;
    setStatus({ kind: "saving", text: "Сохранение…" });
    const request = mode === "simple"
      ? saveSimple(selectedCameraId, profile, simple)
      : savePro(selectedCameraId, profile, pro);
    request
      .then(() => onSaveComplete?.())
      .then(() => setStatus({ kind: "success", text: "Настройки сохранены, инспекция запущена" }))
      .catch((error) => setStatus({ kind: "error", text: errorMessage(error) }));
  };

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
          return (
            <label className="analysis-presets__field" key={field.name}>
              <span><strong>{field.label}</strong><output>{Number(value).toFixed(2)}</output></span>
              <input
                type="range" min={field.name === "threshold" ? 0.01 : 0} max="1" step="0.01"
                value={value} disabled={busy}
                onChange={(event: ChangeEvent<HTMLInputElement>) => {
                  const next = Number(event.target.value);
                  if (mode === "simple") setSimple((current) => ({ ...current, [field.name]: next }));
                  else setPro((current) => ({ ...current, [field.name]: next }));
                }}
              />
              <small>{field.hint}</small>
            </label>
          );
        })}
      </div>

      <div className="analysis-presets__footer">
        <span data-kind={status.kind} aria-live="polite">{status.text}</span>
        <Button type="button" disabled={busy} onClick={save}>Сохранить</Button>
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
}

function loadSimple(cameraId: number | null, profile: string) {
  return cameraId === null ? orchestratorApi.getSimpleAnalysisSettings(profile) : orchestratorApi.getCameraSimpleAnalysisSettings(cameraId);
}
function saveSimple(cameraId: number | null, profile: string, knobs: SimpleAnalysisKnobs) {
  return cameraId === null ? orchestratorApi.setSimpleAnalysisSettings(profile, knobs) : orchestratorApi.setCameraSimpleAnalysisSettings(cameraId, knobs);
}
function loadPro(cameraId: number | null, profile: string) {
  return cameraId === null ? orchestratorApi.getProAnalysisSettings(profile) : orchestratorApi.getCameraProAnalysisSettings(cameraId);
}
function savePro(cameraId: number | null, profile: string, knobs: ProAnalysisKnobs) {
  return cameraId === null ? orchestratorApi.setProAnalysisSettings(profile, knobs) : orchestratorApi.setCameraProAnalysisSettings(cameraId, knobs);
}
