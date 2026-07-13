import { useEffect, useMemo, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import { HttpError, orchestratorApi } from "../../shared/api";
import type { CameraRuntimeSettings, CameraRuntimeSettingsUpdate, CameraTriggerMode } from "../../shared/api";
import { Button } from "../../shared/ui/Button";
import "./CameraSettingsModal.css";

type CameraSettingsModalProps = {
  isOpen: boolean;
  initialCameraId: number | null;
  onClose: () => void;
};

type CameraSettingsScope = "all" | "single";

type CameraSettingsForm = {
  exposure_us: string;
  gain_db: string;
  gamma: string;
  black_level: string;
  capture_trigger_mode: CameraTriggerMode;
  frame_timeout_ms: string;
};

type StatusState = "idle" | "loading" | "saving" | "success" | "error";

const DEFAULT_FORM: CameraSettingsForm = {
  exposure_us: "3000",
  gain_db: "0",
  gamma: "1",
  black_level: "0",
  capture_trigger_mode: "software",
  frame_timeout_ms: "15000",
};

const TRIGGER_MODES: CameraTriggerMode[] = ["continuous", "software", "line0", "line1"];

export function CameraSettingsModal({ isOpen, initialCameraId, onClose }: CameraSettingsModalProps) {
  const [cameraIds, setCameraIds] = useState<number[]>([]);
  const [scope, setScope] = useState<CameraSettingsScope>(initialCameraId === null ? "all" : "single");
  const [selectedCameraId, setSelectedCameraId] = useState(initialCameraId ?? 0);
  const [form, setForm] = useState<CameraSettingsForm>(DEFAULT_FORM);
  const [loadedSettings, setLoadedSettings] = useState<CameraRuntimeSettings | null>(null);
  const [status, setStatus] = useState<{ state: StatusState; text: string }>({
    state: "loading",
    text: "Loading cameras...",
  });

  const targetCameraIds = useMemo(
    () => (scope === "all" ? cameraIds : cameraIds.includes(selectedCameraId) ? [selectedCameraId] : []),
    [cameraIds, scope, selectedCameraId],
  );
  const canSave = status.state !== "loading" && status.state !== "saving" && targetCameraIds.length > 0;

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    let isActive = true;
    orchestratorApi
      .listCameras()
      .then(({ cameras }) => {
        if (!isActive) {
          return;
        }

        const nextCameraIds = cameras.length > 0 ? cameras : initialCameraId === null ? [] : [initialCameraId];
        const sortedCameraIds = [...new Set(nextCameraIds)].sort((left, right) => left - right);
        const nextSelectedCameraId =
          initialCameraId !== null && sortedCameraIds.includes(initialCameraId)
            ? initialCameraId
            : sortedCameraIds[0] ?? 0;
        setCameraIds(sortedCameraIds);
        setSelectedCameraId(nextSelectedCameraId);

        if (sortedCameraIds.length === 0) {
          setStatus({ state: "error", text: "No cameras available" });
          return null;
        }

        return orchestratorApi.getCameraSettings(nextSelectedCameraId);
      })
      .then((settings) => {
        if (!isActive || !settings) {
          return;
        }

        setLoadedSettings(settings);
        setForm(settingsToForm(settings));
        setStatus({ state: "idle", text: "" });
      })
      .catch((error) => {
        if (isActive) {
          setStatus({ state: "error", text: getErrorMessage(error, "Failed to load camera settings") });
        }
      });

    return () => {
      isActive = false;
    };
  }, [initialCameraId, isOpen]);

  useEffect(() => {
    if (!isOpen || scope !== "single" || selectedCameraId < 0) {
      return;
    }

    let isActive = true;
    orchestratorApi
      .getCameraSettings(selectedCameraId)
      .then((settings) => {
        if (!isActive) {
          return;
        }
        setLoadedSettings(settings);
        setForm(settingsToForm(settings));
        setStatus({ state: "idle", text: "" });
      })
      .catch((error) => {
        if (isActive) {
          setStatus({ state: "error", text: getErrorMessage(error, "Failed to load camera settings") });
        }
      });

    return () => {
      isActive = false;
    };
  }, [isOpen, scope, selectedCameraId]);

  const handleFieldChange =
    (fieldName: keyof CameraSettingsForm) => (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setStatus((currentStatus) => (currentStatus.state === "success" ? { state: "idle", text: "" } : currentStatus));
      setForm((currentForm) => ({
        ...currentForm,
        [fieldName]: event.target.value,
      }));
    };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSave) {
      return;
    }

    const update = formToUpdate(form);
    setStatus({ state: "saving", text: "Applying camera settings..." });
    Promise.all(targetCameraIds.map((cameraId) => orchestratorApi.updateCameraSettings(cameraId, update)))
      .then((responses) => {
        const refreshedSettings = responses.find((settings) => settings.camera_id === selectedCameraId) ?? responses[0];
        setLoadedSettings(refreshedSettings ?? null);
        if (refreshedSettings) {
          setForm(settingsToForm(refreshedSettings));
        }
        setStatus({
          state: "success",
          text:
            scope === "all"
              ? `Applied to ${responses.length} cameras`
              : `Applied to camera ${responses[0]?.camera_id ?? selectedCameraId}`,
        });
      })
      .catch((error) => {
        setStatus({ state: "error", text: getErrorMessage(error, "Failed to apply camera settings") });
      });
  };

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="camera-settings"
      onMouseDown={onClose}
    >
      <section
        aria-label="Camera runtime settings"
        aria-modal="true"
        className="camera-settings__modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="camera-settings__header">
          <div>
            <h2>Camera settings</h2>
            <span>Runtime MVS settings</span>
          </div>
          <button
            aria-label="Close camera settings"
            className="camera-settings__close"
            type="button"
            onClick={onClose}
          >
            x
          </button>
        </header>

        <form
          className="camera-settings__form"
          onSubmit={handleSubmit}
        >
          <section className="camera-settings__scope">
            <label>
              <input
                checked={scope === "all"}
                name="camera-settings-scope"
                type="radio"
                value="all"
                onChange={() => setScope("all")}
              />
              <span>All cameras</span>
            </label>
            <label>
              <input
                checked={scope === "single"}
                name="camera-settings-scope"
                type="radio"
                value="single"
                onChange={() => {
                  setStatus({ state: "loading", text: `Loading camera ${selectedCameraId} settings...` });
                  setScope("single");
                }}
              />
              <span>One camera</span>
            </label>
            <select
              aria-label="Camera"
              disabled={scope !== "single" || cameraIds.length === 0}
              value={selectedCameraId}
              onChange={(event) => {
                const nextCameraId = Number(event.target.value);
                setStatus({ state: "loading", text: `Loading camera ${nextCameraId} settings...` });
                setSelectedCameraId(nextCameraId);
              }}
            >
              {cameraIds.map((cameraId) => (
                <option
                  key={cameraId}
                  value={cameraId}
                >
                  Camera {cameraId}
                </option>
              ))}
            </select>
          </section>

          <div className="camera-settings__grid">
            <NumberField
              label="Exposure, us"
              min="1"
              step="1"
              value={form.exposure_us}
              onChange={handleFieldChange("exposure_us")}
            />
            <NumberField
              label="Gain, dB"
              step="0.1"
              value={form.gain_db}
              onChange={handleFieldChange("gain_db")}
            />
            <NumberField
              label="Gamma"
              min="0"
              step="0.01"
              value={form.gamma}
              onChange={handleFieldChange("gamma")}
            />
            <NumberField
              label="Black level"
              step="1"
              value={form.black_level}
              onChange={handleFieldChange("black_level")}
            />
            <label className="camera-settings__field">
              <span>Trigger mode</span>
              <select
                value={form.capture_trigger_mode}
                onChange={handleFieldChange("capture_trigger_mode")}
              >
                {TRIGGER_MODES.map((mode) => (
                  <option
                    key={mode}
                    value={mode}
                  >
                    {mode}
                  </option>
                ))}
              </select>
            </label>
            <NumberField
              label="Frame timeout, ms"
              min="1"
              step="1"
              value={form.frame_timeout_ms}
              onChange={handleFieldChange("frame_timeout_ms")}
            />
          </div>

          {loadedSettings && (
            <dl className="camera-settings__summary">
              <CameraSettingsFact
                label="Loaded camera"
                value={loadedSettings.camera_id}
              />
              <CameraSettingsFact
                label="Effective trigger"
                value={loadedSettings.effective_trigger_mode}
              />
              <CameraSettingsFact
                label="Streaming"
                value={loadedSettings.streaming ? "yes" : "no"}
              />
              <CameraSettingsFact
                label="MVS"
                value={loadedSettings.mvs_available ? "available" : "offline"}
              />
            </dl>
          )}

          {status.text && (
            <div
              aria-live="polite"
              className="camera-settings__status"
              data-state={status.state}
            >
              {status.text}
            </div>
          )}

          <footer className="camera-settings__actions">
            <Button
              disabled={!canSave}
              type="submit"
            >
              Save settings
            </Button>
            <Button
              variant="warning"
              onClick={onClose}
            >
              Close
            </Button>
          </footer>
        </form>
      </section>
    </div>
  );
}

function NumberField({
  label,
  value,
  min,
  step,
  onChange,
}: {
  label: string;
  value: string;
  min?: string;
  step: string;
  onChange: (event: ChangeEvent<HTMLInputElement>) => void;
}) {
  return (
    <label className="camera-settings__field">
      <span>{label}</span>
      <input
        required
        min={min}
        step={step}
        type="number"
        value={value}
        onChange={onChange}
      />
    </label>
  );
}

function CameraSettingsFact({ label, value }: { label: string; value?: string | number }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value ?? "-"}</dd>
    </div>
  );
}

function settingsToForm(settings: CameraRuntimeSettings): CameraSettingsForm {
  return {
    exposure_us: String(settings.exposure_us ?? DEFAULT_FORM.exposure_us),
    gain_db: String(settings.gain_db ?? DEFAULT_FORM.gain_db),
    gamma: String(settings.gamma ?? DEFAULT_FORM.gamma),
    black_level: String(settings.black_level ?? DEFAULT_FORM.black_level),
    capture_trigger_mode: normalizeTriggerMode(settings.capture_trigger_mode),
    frame_timeout_ms: String(settings.frame_timeout_ms ?? DEFAULT_FORM.frame_timeout_ms),
  };
}

function formToUpdate(form: CameraSettingsForm): CameraRuntimeSettingsUpdate {
  return {
    exposure_us: toInteger(form.exposure_us),
    gain_db: toNumber(form.gain_db),
    gamma: toNumber(form.gamma),
    black_level: toInteger(form.black_level),
    capture_trigger_mode: form.capture_trigger_mode,
    frame_timeout_ms: toInteger(form.frame_timeout_ms),
  };
}

function normalizeTriggerMode(value: string | undefined): CameraTriggerMode {
  return TRIGGER_MODES.includes(value as CameraTriggerMode) ? (value as CameraTriggerMode) : "continuous";
}

function toInteger(value: string) {
  return Math.round(toNumber(value));
}

function toNumber(value: string) {
  const numericValue = Number(value);
  return Number.isFinite(numericValue) ? numericValue : 0;
}

function getErrorMessage(error: unknown, fallback: string) {
  if (error instanceof HttpError) {
    return error.responseBody || `${fallback}: ${error.message}`;
  }
  if (error instanceof Error) {
    return `${fallback}: ${error.message}`;
  }
  return fallback;
}
