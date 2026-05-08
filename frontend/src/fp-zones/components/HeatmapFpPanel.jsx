import { memo } from "react";
import { FpRecheckStats } from "./FpRecheckStats";
import { FpZoneList } from "./FpZoneList";

const toSvgPoints = (points) =>
  points.map((point) => `${(point.x * 100).toFixed(2)},${(point.y * 100).toFixed(2)}`).join(" ");

export const HeatmapFpPanel = memo(function HeatmapFpPanel({
  imageSrc,
  containerRef,
  activeContourMode,
  onAddPoint,
  onImageLoad,
  heatmapBox,
  fpZones,
  lastRecheckedZoneIds,
  fpPolygonDraft,
  busy,
  onToggleFpMode,
  onClearDraft,
  onSaveFpZone,
  recheckStats,
  onDeleteZone
}) {
  return (
    <article className="rounded-xl border border-slate-700 bg-panelSoft p-3">
      <h3 className="mb-2 text-sm font-medium text-slate-200">Тепловая карта (разметка FP)</h3>
      <div
        ref={containerRef}
        className={`relative aspect-video overflow-hidden rounded border border-slate-700 bg-black/40 ${
          activeContourMode === "fp" ? "cursor-crosshair" : ""
        }`}
        onClick={onAddPoint}
      >
        {imageSrc ? (
          <>
            <img
              src={imageSrc}
              alt="Тепловая карта"
              className="h-full w-full object-contain"
              onLoad={onImageLoad}
            />
            {heatmapBox.width > 0 && heatmapBox.height > 0 && (
              <svg
                className="pointer-events-none absolute"
                style={{
                  left: `${heatmapBox.left}px`,
                  top: `${heatmapBox.top}px`,
                  width: `${heatmapBox.width}px`,
                  height: `${heatmapBox.height}px`
                }}
                viewBox="0 0 100 100"
                preserveAspectRatio="none"
              >
                {fpZones.map((zone) => {
                  const isRechecked = lastRecheckedZoneIds.includes(zone.id);
                  const points = zone.points_norm_heatmap || [];
                  return (
                    <polygon
                      key={zone.id}
                      points={toSvgPoints(points)}
                      fill={isRechecked ? "rgba(34, 197, 94, 0.28)" : "rgba(14, 165, 233, 0.20)"}
                      stroke={isRechecked ? "rgb(34, 197, 94)" : "rgb(14, 165, 233)"}
                      strokeWidth="2"
                    />
                  );
                })}
                {fpPolygonDraft.length > 0 && (
                  <polygon
                    points={toSvgPoints(fpPolygonDraft)}
                    fill="rgba(250, 204, 21, 0.20)"
                    stroke="rgb(250, 204, 21)"
                    strokeWidth="2"
                  />
                )}
              </svg>
            )}
          </>
        ) : (
          <div className="flex h-full items-center justify-center text-xs text-slate-500">
            Нет данных
          </div>
        )}
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        <button
          onClick={onToggleFpMode}
          disabled={busy || !imageSrc}
          className="rounded-lg bg-cyan-600 px-2 py-1 text-xs font-medium text-white disabled:opacity-60"
        >
          {activeContourMode === "fp" ? "Завершить FP-контур" : "Рисовать FP-контур"}
        </button>
        <button
          onClick={onClearDraft}
          disabled={busy || fpPolygonDraft.length === 0}
          className="rounded-lg bg-slate-700 px-2 py-1 text-xs font-medium disabled:opacity-60"
        >
          Очистить FP-черновик
        </button>
        <button
          onClick={onSaveFpZone}
          disabled={busy || fpPolygonDraft.length < 3 || !imageSrc}
          className="rounded-lg bg-amber-500 px-2 py-1 text-xs font-medium text-slate-950 disabled:opacity-60"
        >
          Сохранить FP-зону
        </button>
      </div>
      <FpRecheckStats recheckStats={recheckStats} />
      <FpZoneList
        zones={fpZones}
        lastRecheckedZoneIds={lastRecheckedZoneIds}
        busy={busy}
        onDelete={onDeleteZone}
      />
    </article>
  );
});
