import { forwardRef, useEffect, useImperativeHandle, useState, useSyncExternalStore } from "react";
import { orchestratorApi } from "../../shared/api";
import { exclusionPointFromImage } from "../../shared/inspectionExclusions";
import type { Exclusions } from "../../shared/inspectionExclusions";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { subscribeAnalysisSettingsChanged } from "../SettingList/analysisSettingsEvents";
import { ExclusionEditorModel } from "./ExclusionEditorModel";
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
}>(function InspectionExclusionsSection({ cameraId, hideSaveAction, onReadyChange }, ref) {
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
  const [imageSize, setImageSize] = useState<{ url: string; width: number; height: number } | null>(null);
  const imageReady = imageSize?.url === reference?.imageUrl && Boolean(imageSize);
  const size = imageReady ? imageSize! : { width: 1, height: 1 };
  const locked = state.busy || !state.ready;
  const drawing = state.points !== null;
  const svgPoints = (points: Exclusions[number]) => points.map(([x, y]) =>
    `${x * (size.width - 1)},${y * (size.height - 1)}`).join(" ");

  useEffect(() => { void model.load(); }, [model]);
  useEffect(() => {
    onReadyChange?.(state.ready && !state.busy);
    return () => onReadyChange?.(false);
  }, [onReadyChange, state.ready, state.busy]);
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
      <p>Внутри розовых зон игнорируются все отличия поверхности, включая новые дефекты, и пересекающие их сечения шва.
        Это отдельная настройка от дообучения. Снаружи зон контроль продолжается.</p>
      <p>Зоны сохраняются при смене эталона. Проверьте их расположение на новом изображении и оставьте небольшой запас вокруг объекта.</p>
      {reference ? (
        <div className="inspection-exclusions__image">
          <img src={reference.imageUrl} alt={`Эталон камеры ${cameraId} для зон исключения`}
            onLoad={(event) => setImageSize({ url: reference.imageUrl,
              width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })}
            onError={() => setImageSize(null)} />
          {imageReady && <svg viewBox={`0 0 ${size.width} ${size.height}`} preserveAspectRatio="xMidYMid meet"
            role="img" aria-label={drawing ? "Отметьте вершины зоны на эталоне" : "Контуры зон на эталоне"}
            data-drawing={drawing} onClick={(event) => {
              if (locked || !drawing) return;
              const point = exclusionPointFromImage(event.clientX, event.clientY,
                event.currentTarget.getBoundingClientRect(), size.width, size.height);
              if (point) model.addPoint(point);
            }}>
            {state.draft.map((polygon, i) => <polygon key={i} points={svgPoints(polygon)}
              data-selected={state.selected === i} onClick={() => model.select(i)} />)}
            {state.points && <>
              <polyline points={svgPoints(state.points)} />
              {state.points.map(([x, y], i) => <circle key={i} cx={x * (size.width - 1)} cy={y * (size.height - 1)}
                r={Math.max(size.width, size.height) / 180} />)}
            </>}
          </svg>}
        </div>
      ) : <p>Для рисования загрузите эталон этой камеры.</p>}
      <div className="inspection-exclusions__actions">
        <button type="button" disabled={locked || drawing || !imageReady || state.draft.length >= 32} onClick={model.start}>Нарисовать зону</button>
        {drawing && <>
          <button type="button" disabled={locked || !state.points?.length} onClick={model.removePoint}>Убрать точку</button>
          <button type="button" disabled={locked || (state.points?.length ?? 0) < 3} onClick={model.finish}>Завершить зону</button>
          <button type="button" disabled={locked} onClick={model.cancelDrawing}>Отмена рисования</button>
          <span>Точек: {state.points?.length}/128</span>
        </>}
      </div>
      <div className="inspection-exclusions__zones" aria-label="Список зон">
        {state.draft.map((polygon, i) => <button key={i} type="button" aria-pressed={state.selected === i}
          disabled={locked || drawing} onClick={() => model.select(i)}>Зона {i + 1} · {polygon.length} точек</button>)}
        {!state.draft.length && <span>Зон нет</span>}
      </div>
      <div className="inspection-exclusions__actions">
        <button type="button" disabled={locked || drawing || state.selected === null} onClick={model.removeSelected}>Удалить выбранную</button>
        <button type="button" disabled={locked || drawing || !state.draft.length} onClick={model.clear}>Удалить все</button>
        <button type="button" disabled={locked || drawing || !state.undo.length} onClick={model.undo}>Отменить изменение</button>
        <button type="button" disabled={locked || (!model.dirty && !drawing)} onClick={model.cancel}>Отменить черновик</button>
        {!hideSaveAction && <button type="button" disabled={locked || drawing || !model.dirty}
          onClick={() => { void model.save().catch(() => undefined); }}>Сохранить зоны</button>}
        {!state.ready && !state.busy && <button type="button" onClick={() => { void model.load(true); }}>Перечитать состояние</button>}
      </div>
      <p role="status" data-error={state.error}>{state.message} Зон: {state.draft.length}/32.</p>
    </section>
  );
});
