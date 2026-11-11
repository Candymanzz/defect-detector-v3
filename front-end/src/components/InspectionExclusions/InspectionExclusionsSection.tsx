import { forwardRef, useCallback, useEffect, useImperativeHandle, useState, useSyncExternalStore } from "react";
import { orchestratorApi } from "../../shared/api";
import type { Exclusions } from "../../shared/inspectionExclusions";
import type { HeatmapDescriptor } from "../../shared/ws";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { subscribeAnalysisSettingsChanged } from "../SettingList/analysisSettingsEvents";
import { ExclusionEditorModel } from "./ExclusionEditorModel";
import { ExclusionImageEditor } from "./ExclusionImageEditor";
import "./InspectionExclusions.css";

export type InspectionExclusionsHandle = {
  save: () => Promise<void>;
  getDraft: () => Exclusions;
  reload: () => Promise<void>;
};

export const InspectionExclusionsSection = forwardRef<InspectionExclusionsHandle, {
  cameraId: number;
  hideSaveAction?: boolean;
  onReadyChange?: (ready: boolean) => void;
  heatmap?: HeatmapDescriptor | null;
  frameId?: string;
  referenceImageUrl?: string;
  currentImageUrl?: string;
}>(function InspectionExclusionsSection({ cameraId, hideSaveAction, onReadyChange, heatmap, frameId, referenceImageUrl, currentImageUrl }, ref) {
  const [model] = useState(() => new ExclusionEditorModel({
    read: async () => (await orchestratorApi.getCameraDetectorSettings(cameraId)).settings.exclusions ?? [],
    write: async (exclusions) => {
      const response = await orchestratorApi.setCameraDetectorSettings(cameraId, { exclusions });
      if (!Array.isArray(response.settings.exclusions)) {
        throw new Error("Сервер не подтвердил зоны. Обновите C++-детектор и Java-оркестратор.");
      }
      return response.settings.exclusions;
    },
  }));
  const state = useSyncExternalStore(model.subscribe, model.getSnapshot, model.getSnapshot);
  const reference = useSyncExternalStore(subscribeReferenceImages,
    () => getReferenceImage(cameraId), () => undefined);
  const [previewSource, setPreviewSource] = useState<"reference" | "current">("reference");
  const imageUrl = previewSource === "current" && currentImageUrl ? currentImageUrl : referenceImageUrl ?? reference?.imageUrl;
  const [loadedImageUrl, setLoadedImageUrl] = useState("");
  const imageReady = Boolean(imageUrl && loadedImageUrl === imageUrl);
  const locked = state.busy || !state.ready || state.dragging;
  const drawing = state.points !== null;
  const endVertexDrag = useCallback((cancel?: boolean) => model.endVertexDrag(cancel), [model]);

  useEffect(() => { void model.load(); }, [model]);
  useEffect(() => {
    onReadyChange?.(state.ready && !state.busy && !state.dragging);
    return () => onReadyChange?.(false);
  }, [onReadyChange, state.ready, state.busy, state.dragging]);
  useEffect(() => subscribeAnalysisSettingsChanged((id) => {
    if ((id === null || id === cameraId) && !model.dirty && !model.getSnapshot().busy && model.getSnapshot().points === null) {
      void model.load();
    }
  }), [cameraId, model]);
  useImperativeHandle(ref, () => ({
    save: () => model.save(), getDraft: () => model.getDraft(), reload: () => model.load(),
  }), [model]);

  return (
    <section className="inspection-exclusions" aria-label="Зоны исключения инспекции">
      <h4>Зоны исключения инспекции</h4>
      <p>Внутри голубых зон игнорируются все отличия поверхности, включая новые дефекты, и пересекающие их сечения шва.
        Это отдельная настройка от дообучения. Снаружи зон контроль продолжается.</p>
      <p>Зоны сохраняются при смене эталона. Проверьте их расположение на новом изображении и оставьте небольшой запас вокруг объекта.</p>
      <div className="inspection-exclusions__toolbar" role="group" aria-label="Подложка для рисования зон">
        <button type="button" aria-pressed={previewSource === "reference"} onClick={() => setPreviewSource("reference")}>Эталон</button>
        <button type="button" aria-pressed={previewSource === "current"} disabled={!currentImageUrl} onClick={() => setPreviewSource("current")}>Текущий кадр</button>
      </div>
      {previewSource === "current" && <p className="inspection-exclusions__hint">Текущий кадр без выравнивания. Хитмап и зоны заданы относительно эталона: при смещении кадра сверьте контур на эталоне.</p>}
      <div className="inspection-exclusions__drawing-tools" data-drawing={drawing}>
        {drawing ? <>
          <strong>Новая зона · {state.points?.length ?? 0}/128 точек</strong>
          <button type="button" className="inspection-exclusions__confirm" disabled={locked || (state.points?.length ?? 0) < 3} onClick={model.finish}>
            Подтвердить зону
          </button>
          <small>Добавьте минимум 3 точки и подтвердите контур. Затем сохраните настройки.</small>
        </> : <button type="button" disabled={locked || !imageReady || state.draft.length >= 32} onClick={model.start}>Добавить зону</button>}
        <div className="inspection-exclusions__actions">
          <button type="button" disabled={locked || !drawing || !state.points?.length} onClick={model.removePoint}>Удалить последнюю точку</button>
          <button type="button" disabled={locked || (drawing ? !state.points?.length : !state.draft.length)} onClick={model.clear}
            title={drawing ? "Удалить все точки новой зоны" : "Удалить все зоны"}>Удалить все</button>
          {drawing && <button type="button" disabled={locked} onClick={model.cancelDrawing}>Отменить рисование</button>}
        </div>
      </div>
      {imageUrl ? (
        <ExclusionImageEditor key={`${imageUrl}:${frameId ?? ""}`} imageUrl={imageUrl} cameraId={cameraId} heatmap={heatmap} frameId={frameId}
          currentFrame={previewSource === "current"}
          polygons={state.draft} points={state.points} selected={state.selected} disabled={state.busy || !state.ready}
          onImageReady={setLoadedImageUrl} onAddPoint={point => model.addPoint(point)} onSelect={index => model.select(index)}
          onDragStart={index => model.beginVertexDrag(index)} onDrag={point => model.dragVertex(point)} onDragEnd={endVertexDrag} />
      ) : <p>Для рисования загрузите эталон этой камеры.</p>}
      <div className="inspection-exclusions__zones" aria-label="Список зон">
        {state.draft.map((polygon, i) => <button key={i} type="button" aria-pressed={state.selected === i}
          disabled={locked || drawing} onClick={() => model.select(i)}>Зона {i + 1} · {polygon.length} точек</button>)}
        {!state.draft.length && <span>Подтверждённых зон нет</span>}
      </div>
      <div className="inspection-exclusions__actions">
        <button type="button" disabled={locked || drawing || state.selected === null} onClick={model.removeSelected}>Удалить выбранную</button>
        <button type="button" disabled={locked || drawing || !state.undo.length} onClick={model.undo}>Отменить изменение</button>
        <button type="button" disabled={locked || (!model.dirty && !drawing)} onClick={model.cancel}>Отменить изменения</button>
        {!hideSaveAction && <button type="button" disabled={locked || drawing || !model.dirty}
          onClick={() => { void model.save().catch(() => undefined); }}>Сохранить зоны</button>}
        {!state.ready && !state.busy && <button type="button" onClick={() => { void model.load(true); }}>Перечитать состояние</button>}
      </div>
      <p role="status" data-error={state.error}>{state.message} Зон: {state.draft.length}/32.</p>
    </section>
  );
});
