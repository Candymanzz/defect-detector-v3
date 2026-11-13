import { useState, useSyncExternalStore } from "react";
import type { DetectorSettings } from "../../shared/api";
import type { ExclusionPoint } from "../../shared/inspectionExclusions";
import { validateExclusions } from "../../shared/inspectionExclusions";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { ExclusionImageEditor } from "../InspectionExclusions/ExclusionImageEditor";

export function LabelTrackingSection({ cameraId, settings, disabled, imageUrl: suppliedImage, onChange }: {
  cameraId: number; settings: DetectorSettings; disabled: boolean; imageUrl?: string;
  onChange: (patch: Partial<DetectorSettings>) => void;
}) {
  const reference = useSyncExternalStore(subscribeReferenceImages,
    () => getReferenceImage(cameraId), () => undefined);
  const imageUrl = suppliedImage ?? reference?.imageUrl;
  const [readyImage, setReadyImage] = useState("");
  const [points, setPoints] = useState<ExclusionPoint[] | null>(null);
  const [error, setError] = useState("");
  const outline = settings.label_outline ?? [];
  const enabled = outline.length >= 3 && settings.label_tracking_enabled !== false;
  function confirm() {
    const validation = validateExclusions([points]);
    if (validation || !points) { setError(validation ?? "Обведите этикетку."); return; }
    onChange({ label_outline: points.map(([x,y]) => [x,y]), label_tracking_enabled: true, label_confirmation_id: Date.now().toString(36) + "-" + Math.random().toString(36).slice(2) });
    setPoints(null); setError("");
  }
  return <section className="inspection-exclusions" aria-label="Контур этикетки">
    <h4>Компенсация по контуру этикетки</h4>
    <p>Обведите саму этикетку на эталоне, исключив пластик ведра. При сдвиге проверяется общая видимая область.
      Запас — 1 px исходного кадра, независимо от ослабления сравнения. Изменения применяются при сохранении настроек.</p>
    <label><input type="checkbox" checked={enabled} disabled={disabled || points !== null}
      onChange={event => {
        if (event.target.checked && outline.length < 3) { setError("Сначала обведите и подтвердите контур этикетки."); return; }
        onChange({ label_tracking_enabled: event.target.checked }); setError("");
      }} /> Компенсация по контуру этикетки</label>
    <div className="inspection-exclusions__actions">
      <button type="button" disabled={disabled || !imageUrl || readyImage !== imageUrl || points !== null}
        onClick={() => { setPoints([]); setError(""); }}>Обвести этикетку заново</button>
      {points !== null && <>
        <button type="button" disabled={disabled || points.length < 3} onClick={confirm}>Подтвердить контур этикетки</button>
        <button type="button" disabled={disabled || !points.length} onClick={() => setPoints(points.slice(0,-1))}>Убрать точку</button>
        <button type="button" disabled={disabled} onClick={() => { setPoints(null); setError(""); }}>Отменить рисование</button>
      </>}
      <button type="button" disabled={disabled || !outline.length || points !== null}
        onClick={() => onChange({ label_outline: [] })}>Удалить контур этикетки</button>
    </div>
    {imageUrl ? <ExclusionImageEditor imageUrl={imageUrl} cameraId={cameraId}
      polygons={outline.length ? [outline] : []} points={points} selected={null} disabled={disabled}
      onImageReady={setReadyImage} onAddPoint={point => setPoints(current => current && current.length < 128 ? [...current,point] : current)}
      onSelect={() => undefined} onDragStart={() => false} onDrag={() => undefined} onDragEnd={() => undefined} />
      : <p>Для рисования загрузите эталон этой камеры.</p>}
    {points !== null && <p>Контур ещё не подтверждён. Точек: {points.length}/128.</p>}
    <p role="status">{error || (enabled ? "Компенсация включена." : "Компенсация выключена; сохранённый контур можно включить повторно.")}</p>
    <p>После смены эталона подтвердите контур заново.</p>
  </section>;
}
