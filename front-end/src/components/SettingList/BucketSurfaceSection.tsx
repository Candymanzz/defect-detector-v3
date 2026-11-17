import { useState } from "react";
import type { DetectorSettings } from "../../shared/api";
import type { ExclusionPoint } from "../../shared/inspectionExclusions";
import { validateExclusions } from "../../shared/inspectionExclusions";
import { ExclusionImageEditor } from "../InspectionExclusions/ExclusionImageEditor";

export function BucketSurfaceSection({ cameraId, settings, disabled, imageUrl, onChange }: {
  cameraId: number; settings: DetectorSettings; disabled: boolean; imageUrl?: string;
  onChange: (patch: Partial<DetectorSettings>) => void;
}) {
  const outline=settings.bucket_outline ?? [];
  const [points,setPoints]=useState<ExclusionPoint[] | null>(null);
  const [readyImage,setReadyImage]=useState("");
  const [error,setError]=useState("");
  function confirm() {
    const validation=validateExclusions([points]);
    if(validation || !points) { setError(validation ?? "Обведите поверхность ведра.");return; }
    onChange({bucket_outline:points.map(([x,y])=>[x,y]),
      bucket_confirmation_id:Date.now().toString(36)+"-"+Math.random().toString(36).slice(2)});
    setPoints(null);setError("");
  }
  return <details className="label-tracking__editor bucket-surface" aria-label="Видимая поверхность ведра">
    <summary>Видимая поверхность ведра · {outline.length>=3 ? "контур задан" : "контур не задан"}</summary>
    <p>Обведите физический силуэт видимой поверхности ведра, включая открытый пластик.
      Это отдельный контур: он не должен повторять рисунок, повреждения этикетки или границу ROI.</p>
    <p>При включённой компенсации перемещение ведра отслеживается отдельно от этикетки.
      Участки за силуэтом исключаются; внутри видимой этикетки дефекты продолжают проверяться.
      Если положение ведра не подтверждается, сравнение останавливается.</p>
    <small>Поддерживается перенос ведра по горизонтали и вертикали. Значительный наклон или изменение формы требуют отдельной проверки.</small>
    {error && <p className="label-tracking__error" role="alert">{error}</p>}
    <div className="inspection-exclusions__actions">
      <button type="button" disabled={disabled || !imageUrl || readyImage!==imageUrl || points!==null}
        onClick={()=>{setPoints([]);setError("");}}>Обвести поверхность ведра</button>
      {points!==null && <>
        <button type="button" disabled={disabled || points.length<3} onClick={confirm}>Подтвердить поверхность ведра</button>
        <button type="button" disabled={disabled || points.length===0} onClick={()=>setPoints(points.slice(0,-1))}>Убрать точку</button>
        <button type="button" disabled={disabled} onClick={()=>{setPoints(null);setError("");}}>Отменить рисование</button>
      </>}
      <button type="button" disabled={disabled || outline.length===0 || points!==null}
        onClick={()=>onChange({bucket_outline:[]})}>Удалить контур ведра</button>
    </div>
    {imageUrl ? <ExclusionImageEditor imageUrl={imageUrl} cameraId={cameraId}
      polygons={outline.length ? [outline] : []} points={points} selected={null} disabled={disabled}
      onImageReady={setReadyImage} onAddPoint={point=>setPoints(current=>current && current.length<128 ? [...current,point] : current)}
      onSelect={()=>undefined} onDragStart={()=>false} onDrag={()=>undefined} onDragEnd={()=>undefined} />
      : <p>Для рисования загрузите эталон этой камеры.</p>}
    {points!==null && <p>Контур ещё не подтверждён. Точек: {points.length}/128.</p>}
    <p>Если положение ведра не определено, инспекция продолжится с предупреждением и обычным позиционированием этикетки, без обрезки по движению ведра. Допуск проверяется по общему сдвигу.</p>
    <p>После смены эталона подтвердите оба контура заново. При удалении контура ведра остаётся сравнение по контуру этикетки.</p>
  </details>;
}
