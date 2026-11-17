import { useState, useSyncExternalStore } from "react";
import type { DetectorSettings } from "../../shared/api";
import type { ExclusionPoint } from "../../shared/inspectionExclusions";
import { validateExclusions } from "../../shared/inspectionExclusions";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { ExclusionImageEditor } from "../InspectionExclusions/ExclusionImageEditor";
import { BucketSurfaceSection } from "./BucketSurfaceSection";
import "./LabelTrackingSection.css";

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
  return <section className="inspection-exclusions label-tracking" aria-label="Компенсация с вырезанием краёв" data-enabled={enabled}>
    <label className="label-tracking__toggle" data-disabled={disabled}>
      <input type="checkbox" checked={enabled} disabled={disabled}
        aria-describedby={`label-tracking-mode-${cameraId}`}
      onChange={event => {
        if (event.target.checked && outline.length < 3) { setError("Сначала обведите и подтвердите контур этикетки."); return; }
        onChange({ label_tracking_enabled: event.target.checked }); setError("");
      }} />
      <span className="label-tracking__title">Компенсация с вырезанием краёв</span>
      <span className="label-tracking__badge" aria-hidden="true">{enabled ? "Включена" : "Выключена"}</span>
    </label>
    <p id={`label-tracking-mode-${cameraId}`} className="label-tracking__mode">{enabled
      ? (settings.bucket_outline?.length ?? 0)>=3
        ? "Ведро и этикетка позиционируются отдельно. Проверяется общая видимая поверхность; контур этикетки не подстраивается под её повреждения. Допуски в мм относятся к смещению этикетки относительно ведра; запас по краю — 1 px исходного кадра."
        : "После позиционирования проверяется общая видимая часть этикетки. Граница автоматически уточняется рядом с сохранённым контуром. Открывающиеся участки ведра исключаются из сравнения; запас по краю — 1 px исходного кадра."
      : "Обычное позиционирование и сравнение ROI. Вырезание по контуру этикетки и запас по краю отключены."}</p>
    <small className="label-tracking__save-hint">Настройка сохраняется для выбранной камеры вместе с остальными настройками анализа.</small>
    {error && <p className="label-tracking__error" role="alert">{error}</p>}
    <details className="label-tracking__editor">
    <summary>Настроить контур этикетки{outline.length < 3 ? " · контур не задан" : ""}</summary>
    <p>Обведите саму этикетку на эталоне, исключив пластик ведра. Запас не зависит от ослабления сравнения.
      Подтверждение контура включает вырезание.</p>
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
    <p>После смены эталона подтвердите контур заново.</p>
    </details>
    <BucketSurfaceSection key={`${cameraId}:${imageUrl ?? ""}`} cameraId={cameraId} settings={settings}
      disabled={disabled} imageUrl={imageUrl} onChange={onChange} />
  </section>;
}
