import { useState } from "react";
import { orchestratorApi } from "../../shared/api";
import type { CameraRuntimeSettings } from "../../shared/api";
import { Button } from "../../shared/ui/Button";

type Roi = { roi_x: number; roi_y: number; roi_width: number; roi_height: number };
const currentRoi = (settings: CameraRuntimeSettings): Roi => ({
  roi_x: settings.roi_x ?? 0,
  roi_y: settings.roi_y ?? 0,
  roi_width: settings.roi_width ?? settings.width ?? 1,
  roi_height: settings.roi_height ?? settings.height ?? 1,
});

export function CameraCaptureRoiSettings({
  settings,
  enabled,
  onSaved,
  onBusyChange,
}: {
  settings: CameraRuntimeSettings;
  enabled: boolean;
  onSaved: (settings: CameraRuntimeSettings) => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const [draft, setRoi] = useState<Roi | null>(null);
  const roi = draft ?? currentRoi(settings);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  if (!settings.roi_supported) return null;
  const sw = settings.roi_sensor_width ?? settings.width ?? 1;
  const sh = settings.roi_sensor_height ?? settings.height ?? 1;
  const disabled = !enabled || busy || settings.streaming;
  const fields: { key: keyof Roi; label: string; min: number; max: number; step: number }[] = [
    {
      key: "roi_x",
      label: "Слева, X",
      min: settings.roi_x_min ?? 0,
      max: sw - roi.roi_width,
      step: settings.roi_x_inc ?? 1,
    },
    {
      key: "roi_y",
      label: "Сверху, Y",
      min: settings.roi_y_min ?? 0,
      max: sh - roi.roi_height,
      step: settings.roi_y_inc ?? 1,
    },
    {
      key: "roi_width",
      label: "Ширина",
      min: settings.roi_width_min ?? 1,
      max: Math.min(settings.roi_width_max ?? sw, sw - roi.roi_x),
      step: settings.roi_width_inc ?? 1,
    },
    {
      key: "roi_height",
      label: "Высота",
      min: settings.roi_height_min ?? 1,
      max: Math.min(settings.roi_height_max ?? sh, sh - roi.roi_y),
      step: settings.roi_height_inc ?? 1,
    },
  ];
  const valid = fields.every(
    ({ key, min, max, step }) =>
      Number.isInteger(roi[key]) && roi[key] >= min && roi[key] <= max && (roi[key] - min) % step === 0,
  );
  const unchanged = JSON.stringify(roi) === JSON.stringify(currentRoi(settings));
  const save = async () => {
    if (disabled || !valid || unchanged) return;
    setBusy(true);
    onBusyChange(true);
    setMessage("");
    try {
      // Old reference pixels and ROI coordinates cannot be reused after moving the sensor crop.
      await orchestratorApi.resetInspection();
      const applied = await orchestratorApi.updateCameraSettings(settings.camera_id, roi);
      setRoi(null);
      onSaved(applied);
      setMessage("Область захвата сохранена для этой камеры. Снимите новый эталон.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Не удалось изменить область захвата");
    } finally {
      setBusy(false);
      onBusyChange(false);
    }
  };
  return (
    <section
      className="camera-settings__roi"
      aria-label="Область захвата камеры"
    >
      <h3>Область захвата камеры {settings.camera_id}</h3>
      <p>
        Камера передаёт только этот прямоугольник. Координаты и размеры — в пикселях после binning. Применение остановит
        инспекцию и сбросит эталон: затем снимите новый.
      </p>
      {!enabled && <p>Для настройки области выберите «Одна камера».</p>}
      {settings.streaming && <p>Остановите видеопоток перед изменением области.</p>}
      <fieldset
        disabled={disabled}
        className="camera-settings__grid"
      >
        {fields.map(({ key, label, min, max, step }) => (
          <label
            className="camera-settings__field"
            key={key}
          >
            <span>{label}, px</span>
            <input
              type="number"
              value={Number.isNaN(roi[key]) ? "" : roi[key]}
              min={min}
              max={max}
              step={step}
              onChange={(event) =>
                setRoi({ ...roi, [key]: event.target.value === "" ? NaN : Number(event.target.value) })
              }
            />
            <small>
              {min}–{max}, шаг {step}
            </small>
          </label>
        ))}
      </fieldset>
      <svg
        className="camera-settings__roi-map"
        viewBox={`0 0 ${sw} ${sh}`}
        role="img"
        aria-label="Расположение области на матрице"
      >
        <rect
          width={sw}
          height={sh}
          fill="#182334"
        />
        {valid && (
          <rect
            x={roi.roi_x}
            y={roi.roi_y}
            width={roi.roi_width}
            height={roi.roi_height}
            fill="#23b9a54d"
            stroke="#4dddc5"
            strokeWidth={Math.max(sw, sh) / 200}
          />
        )}
      </svg>
      <p>
        Матрица: {sw} × {sh}. Максимальный кадр: {settings.roi_width_max} × {settings.roi_height_max}.
      </p>
      <div className="camera-settings__actions">
        <Button
          type="button"
          disabled={disabled}
          onClick={() =>
            setRoi({
              roi_x: settings.roi_default_x ?? 0,
              roi_y: settings.roi_default_y ?? 0,
              roi_width: settings.roi_width_max ?? sw,
              roi_height: settings.roi_height_max ?? sh,
            })
          }
        >
          Исходная область
        </Button>
        <Button
          type="button"
          disabled={disabled || !valid || unchanged}
          onClick={() => void save()}
        >
          {busy ? "Применение…" : "Применить область и сбросить эталон"}
        </Button>
      </div>
      {message && <p role="status">{message}</p>}
    </section>
  );
}
