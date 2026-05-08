import { memo, useCallback, useMemo } from "react";

const SVG_VIEWBOX = "0 0 100 100";
const POINT_STROKE = "rgb(255, 255, 255)";
const TARGET_COVERAGE_MIN = 20;
const TARGET_COVERAGE_MAX = 30;

const POLYGON_PRESETS = [
  {
    key: "item",
    fillColor: "rgba(59, 130, 246, 0.18)",
    strokeColor: "rgb(59, 130, 246)",
    pointColor: "rgb(59, 130, 246)"
  },
  {
    key: "roi",
    fillColor: "rgba(16, 185, 129, 0.22)",
    strokeColor: "rgb(16, 185, 129)",
    pointColor: "rgb(251, 191, 36)"
  }
];

const BUCKET_FIELDS = [
  { name: "topRadius", label: "R верх", min: 1 },
  { name: "bottomRadius", label: "R низ", min: 1 },
  { name: "height", label: "H", min: 1 }
];

const PROJECTION_FIELDS = [
  { name: "pixelTopY", label: "Y верх(px)" },
  { name: "pixelBottomY", label: "Y низ(px)" },
  { name: "pixelCenterX", label: "X центр(px)" }
];

const scalePoint = (point) => ({
  x: (point.x * 100).toFixed(2),
  y: (point.y * 100).toFixed(2)
});

const buildSvgPoints = (scaled) => scaled.map((p) => `${p.x},${p.y}`).join(" ");

const isCoverageOnTarget = (percent) =>
  percent >= TARGET_COVERAGE_MIN && percent <= TARGET_COVERAGE_MAX;

const coverageColorClass = (percent) =>
  isCoverageOnTarget(percent) ? "text-emerald-300" : "text-amber-300";

const PolygonOverlay = memo(function PolygonOverlay({ referenceBox, roiPolygon, itemPolygon }) {
  const overlays = useMemo(() => {
    const sources = { item: itemPolygon, roi: roiPolygon };
    return POLYGON_PRESETS.map((preset) => {
      const scaled = sources[preset.key].map(scalePoint);
      return {
        ...preset,
        scaled,
        svgPoints: buildSvgPoints(scaled)
      };
    });
  }, [itemPolygon, roiPolygon]);

  const style = useMemo(
    () => ({
      left: `${referenceBox.left}px`,
      top: `${referenceBox.top}px`,
      width: `${referenceBox.width}px`,
      height: `${referenceBox.height}px`
    }),
    [referenceBox.left, referenceBox.top, referenceBox.width, referenceBox.height]
  );

  const hasBox = referenceBox.width > 0 && referenceBox.height > 0;
  const hasPolygons = overlays.some((overlay) => overlay.scaled.length > 0);

  if (!hasBox || !hasPolygons) return null;

  return (
    <svg
      className="pointer-events-none absolute"
      style={style}
      viewBox={SVG_VIEWBOX}
      preserveAspectRatio="none"
    >
      {overlays.map((overlay) =>
        overlay.scaled.length > 0 ? (
          <polygon
            key={overlay.key}
            points={overlay.svgPoints}
            fill={overlay.fillColor}
            stroke={overlay.strokeColor}
            strokeWidth="2"
          />
        ) : null
      )}
      {overlays.flatMap((overlay) =>
        overlay.scaled.map((point, index) => (
          <circle
            key={`${overlay.key}-${point.x}-${point.y}-${index}`}
            cx={point.x}
            cy={point.y}
            r="2.2"
            fill={overlay.pointColor}
            stroke={POINT_STROKE}
            strokeWidth="0.7"
          />
        ))
      )}
    </svg>
  );
});

const CompactNumberInput = memo(function CompactNumberInput({
  name,
  label,
  value,
  min,
  onChange
}) {
  const handleChange = (event) => onChange(name, Number(event.target.value));

  return (
    <label className="flex items-center gap-1 rounded bg-slate-800 px-2 py-1">
      <span className="whitespace-nowrap text-[11px] text-slate-300">{label}</span>
      <input
        type="number"
        min={min}
        step={1}
        value={value}
        onChange={handleChange}
        className="w-full bg-transparent text-right text-xs text-slate-100 outline-none"
      />
    </label>
  );
});

export const ReferenceTemplatePanel = memo(function ReferenceTemplatePanel({
  referencePreview,
  referenceContainerRef,
  activeContourMode,
  onAddPolygonPoint,
  onReferenceImageLoad,
  referenceBox,
  roiPolygon,
  itemPolygon,
  busy,
  onToggleRoiMode,
  onToggleItemMode,
  onClearRoiPolygon,
  onClearItemPolygon,
  onSavePolygonRoi,
  roiArea,
  itemArea,
  roiCoveragePercent,
  bucketGeometry,
  onBucketGeometryChange,
  bucketSideArea,
  projectionParams,
  onProjectionParamsChange,
  physicalAreaStats
}) {
  const handleBucketChange = useCallback(
    (name, value) => onBucketGeometryChange((prev) => ({ ...prev, [name]: value })),
    [onBucketGeometryChange]
  );

  const handleProjectionChange = useCallback(
    (name, value) => onProjectionParamsChange((prev) => ({ ...prev, [name]: value })),
    [onProjectionParamsChange]
  );

  const canvasClassName = `relative h-36 overflow-hidden rounded border border-slate-700 bg-black/40 ${
    activeContourMode ? "cursor-crosshair" : ""
  }`;

  return (
    <div className="rounded-xl border border-slate-700 bg-panelSoft p-3">
      <p className="mb-2 text-sm text-slate-300">Golden Template</p>
      <div ref={referenceContainerRef} className={canvasClassName} onClick={onAddPolygonPoint}>
        {referencePreview ? (
          <>
            <img
              src={referencePreview}
              alt="reference"
              className="h-full w-full object-contain"
              onLoad={onReferenceImageLoad}
            />
            <PolygonOverlay
              referenceBox={referenceBox}
              roiPolygon={roiPolygon}
              itemPolygon={itemPolygon}
            />
          </>
        ) : (
          <div className="flex h-full items-center justify-center text-xs text-slate-500">
            Эталон не задан
          </div>
        )}
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        <button
          onClick={onToggleRoiMode}
          disabled={busy || !referencePreview}
          className="rounded-lg bg-emerald-600 px-2 py-1 text-xs font-medium text-white disabled:opacity-60"
        >
          {activeContourMode === "roi" ? "Завершить ROI" : "Рисовать ROI контур"}
        </button>
        <button
          onClick={onToggleItemMode}
          disabled={busy || !referencePreview}
          className="rounded-lg bg-blue-600 px-2 py-1 text-xs font-medium text-white disabled:opacity-60"
        >
          {activeContourMode === "item" ? "Завершить контур изделия" : "Рисовать контур изделия"}
        </button>
        <button
          onClick={onClearRoiPolygon}
          disabled={busy || roiPolygon.length === 0}
          className="rounded-lg bg-slate-700 px-2 py-1 text-xs font-medium disabled:opacity-60"
        >
          Очистить контур
        </button>
        <button
          onClick={onClearItemPolygon}
          disabled={busy || itemPolygon.length === 0}
          className="rounded-lg bg-slate-700 px-2 py-1 text-xs font-medium disabled:opacity-60"
        >
          Очистить контур изделия
        </button>
        <button
          onClick={onSavePolygonRoi}
          disabled={busy || roiPolygon.length < 3}
          className="rounded-lg bg-amber-500 px-2 py-1 text-xs font-medium text-slate-950 disabled:opacity-60"
        >
          Сохранить контур ROI
        </button>
      </div>
      <div className="mt-2 rounded-md border border-slate-700 bg-slate-900/60 px-2 py-2 text-xs text-slate-300">
        <div>Площадь ROI: {(roiArea * 100).toFixed(2)}% кадра</div>
        <div>Площадь изделия: {(itemArea * 100).toFixed(2)}% кадра</div>
        <div className={coverageColorClass(roiCoveragePercent)}>
          ROI от изделия: {roiCoveragePercent.toFixed(1)}% (цель ~25%)
        </div>
        <div className="mt-2 border-t border-slate-700 pt-2">
          <div className="mb-1 text-slate-400">Параметры ведра (мм)</div>
          <div className="grid grid-cols-3 gap-2">
            {BUCKET_FIELDS.map((field) => (
              <CompactNumberInput
                key={field.name}
                name={field.name}
                label={field.label}
                value={bucketGeometry[field.name]}
                min={field.min}
                onChange={handleBucketChange}
              />
            ))}
          </div>
          <div className="mt-1 text-[11px] text-slate-400">
            Боковая площадь (усеченный конус): {bucketSideArea > 0 ? bucketSideArea.toFixed(0) : 0}{" "}
            мм²
          </div>
          <div className="mt-2 grid grid-cols-3 gap-2">
            {PROJECTION_FIELDS.map((field) => (
              <CompactNumberInput
                key={field.name}
                name={field.name}
                label={field.label}
                value={projectionParams[field.name]}
                onChange={handleProjectionChange}
              />
            ))}
          </div>
          <div className="mt-2 border-t border-slate-700 pt-2 text-[11px] text-slate-300">
            <div>ROI (оценка в реальности): {physicalAreaStats.roiMm2.toFixed(0)} мм²</div>
            <div>Изделие (оценка по контуру): {physicalAreaStats.itemMm2.toFixed(0)} мм²</div>
            <div>Полная боковая площадь: {physicalAreaStats.fullSideAreaMm2.toFixed(0)} мм²</div>
            <div>
              Видимая боковая площадь (~160°): {physicalAreaStats.visibleSideAreaMm2.toFixed(0)} мм²
            </div>
            <div className={coverageColorClass(physicalAreaStats.coverageFromVisibleSidePercent)}>
              ROI от видимой боковой площади:{" "}
              {physicalAreaStats.coverageFromVisibleSidePercent.toFixed(1)}% (цель ~25%)
            </div>
            <div className="text-slate-400">
              ROI от синего контура изделия: {physicalAreaStats.coveragePercent.toFixed(1)}%
            </div>
            <div className="text-slate-400">
              Оценочная погрешность модели: ±{physicalAreaStats.errorPercent}%
            </div>
          </div>
        </div>
      </div>
    </div>
  );
});
