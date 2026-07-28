import { useRef, useState } from "react";
import type { MouseEvent, PointerEvent } from "react";
import type { FpZoneNorm, InterestPointNorm } from "../../shared/ws";
import "./FpZoneEditor.css";

type DrawMode = "polygon" | "radius";

type FpZoneEditorProps = {
  imageUrl: string;
  roiPoints?: InterestPointNorm[];
  zones: FpZoneNorm[];
  disabled?: boolean;
  onChange: (zones: FpZoneNorm[]) => void;
};

type DragState = {
  zoneIndex: number;
  pointIndex: number;
  points: InterestPointNorm[];
};

export function FpZoneEditor({
  imageUrl,
  roiPoints = [],
  zones,
  disabled = false,
  onChange,
}: FpZoneEditorProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const dragRef = useRef<DragState | null>(null);
  const ignoreNextClickRef = useRef(false);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [drawMode, setDrawMode] = useState<DrawMode>("polygon");
  const [cursorPoint, setCursorPoint] = useState<InterestPointNorm | null>(null);
  const safeSelectedIndex = zones.length === 0 ? -1 : Math.min(selectedIndex, zones.length - 1);
  const selectedZone = safeSelectedIndex >= 0 ? zones[safeSelectedIndex] : undefined;
  const selectedEdgeHandles = selectedZone ? createEdgeHandles(selectedZone.points_norm_heatmap) : [];
  const roiSvgPoints = roiPoints.map(toSvgPoint).join(" ");

  const updateZone = (zoneIndex: number, update: Partial<FpZoneNorm>) => {
    onChange(zones.map((zone, index) => (index === zoneIndex ? { ...zone, ...update } : zone)));
  };

  const resolveNormPoint = (event: MouseEvent<Element> | PointerEvent<Element>): InterestPointNorm | null => {
    const rect = imageRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return null;

    return {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };
  };

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (ignoreNextClickRef.current) {
      ignoreNextClickRef.current = false;
      return;
    }
    if (disabled || drawMode === "radius") return;

    const nextPoint = resolveNormPoint(event);
    if (!nextPoint) return;

    if (!selectedZone) {
      onChange([createEmptyZone([nextPoint])]);
      setSelectedIndex(0);
      return;
    }

    updateZone(safeSelectedIndex, {
      points_norm_heatmap: [...selectedZone.points_norm_heatmap, nextPoint],
    });
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const point = resolveNormPoint(event);
    setCursorPoint(point);

    const drag = dragRef.current;
    if (!drag || !point) return;

    const nextPoints = drag.points.map((currentPoint, index) =>
      index === drag.pointIndex ? point : currentPoint,
    );
    dragRef.current = { ...drag, points: nextPoints };
    updateZone(drag.zoneIndex, { points_norm_heatmap: nextPoints });
  };

  const beginDrag = (
    event: PointerEvent<SVGCircleElement>,
    zoneIndex: number,
    pointIndex: number,
    nextPoints = zones[zoneIndex]?.points_norm_heatmap ?? [],
  ) => {
    if (disabled || drawMode !== "radius") return;

    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    ignoreNextClickRef.current = true;
    dragRef.current = { zoneIndex, pointIndex, points: nextPoints };
    setSelectedIndex(zoneIndex);

    if (nextPoints !== zones[zoneIndex]?.points_norm_heatmap) {
      updateZone(zoneIndex, { points_norm_heatmap: nextPoints });
    }
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const handleAddZone = () => {
    onChange([...zones, createEmptyZone()]);
    setSelectedIndex(zones.length);
  };

  const handleRemoveLastPoint = () => {
    if (!selectedZone) return;
    updateZone(safeSelectedIndex, {
      points_norm_heatmap: selectedZone.points_norm_heatmap.slice(0, -1),
    });
  };

  const handleClearZone = () => {
    if (!selectedZone) return;
    updateZone(safeSelectedIndex, { points_norm_heatmap: [] });
  };

  const handleDeleteZone = () => {
    if (!selectedZone) return;
    onChange(zones.filter((_, index) => index !== safeSelectedIndex));
    setSelectedIndex(Math.max(0, safeSelectedIndex - 1));
  };

  const handleToggleRadiusMode = () => {
    dragRef.current = null;
    setDrawMode((current) => (current === "radius" ? "polygon" : "radius"));
  };

  return (
    <div className="fp-zone-editor">
      <div
        className={`fp-zone-editor__canvas${drawMode === "radius" ? " fp-zone-editor__canvas--radius" : ""}`}
        onClick={handleCanvasClick}
        onPointerMove={handlePointerMove}
        onPointerLeave={() => {
          if (!dragRef.current) setCursorPoint(null);
        }}
      >
        <img
          ref={imageRef}
          src={imageUrl}
          alt="Исключающие зоны"
        />
        <svg
          className="fp-zone-editor__overlay"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        >
          {roiPoints.length >= 3 && (
            <polygon
              className="fp-zone-editor__roi"
              points={roiSvgPoints}
            />
          )}

          {zones.map((zone, zoneIndex) => {
            const points = zone.points_norm_heatmap.map(toSvgPoint).join(" ");
            const isSelected = zoneIndex === safeSelectedIndex;

            return (
              <g
                key={zone.id ?? zoneIndex}
                className={isSelected ? "fp-zone-editor__zone fp-zone-editor__zone--selected" : "fp-zone-editor__zone"}
              >
                {zone.points_norm_heatmap.length >= 3 && <polygon points={points} />}
                {zone.points_norm_heatmap.length >= 2 && <polyline points={points} />}
                {zone.points_norm_heatmap.map((point, pointIndex) => (
                  <circle
                    key={`${zone.id ?? zoneIndex}-${pointIndex}`}
                    cx={point.x}
                    cy={point.y}
                    r="0.012"
                  />
                ))}
              </g>
            );
          })}

          {!disabled && cursorPoint && (
            <g className="fp-zone-editor__cursor">
              <line x1={cursorPoint.x - 0.02} y1={cursorPoint.y} x2={cursorPoint.x + 0.02} y2={cursorPoint.y} />
              <line x1={cursorPoint.x} y1={cursorPoint.y - 0.02} x2={cursorPoint.x} y2={cursorPoint.y + 0.02} />
            </g>
          )}

          {drawMode === "radius" &&
            selectedZone &&
            selectedEdgeHandles.map((handle) => (
              <circle
                key={`edge-${safeSelectedIndex}-${handle.insertIndex}`}
                className="fp-zone-editor__edge-handle"
                cx={handle.point.x}
                cy={handle.point.y}
                r="0.015"
                onPointerDown={(event) => {
                  const nextPoints = [...selectedZone.points_norm_heatmap];
                  nextPoints.splice(handle.insertIndex, 0, handle.point);
                  beginDrag(event, safeSelectedIndex, handle.insertIndex, nextPoints);
                }}
              />
            ))}

          {drawMode === "radius" &&
            selectedZone?.points_norm_heatmap.map((point, pointIndex) => (
              <circle
                key={`drag-${selectedZone.id ?? safeSelectedIndex}-${pointIndex}`}
                className="fp-zone-editor__vertex-handle"
                cx={point.x}
                cy={point.y}
                r="0.012"
                onPointerDown={(event) => beginDrag(event, safeSelectedIndex, pointIndex)}
              />
            ))}
        </svg>
      </div>

      <div className="fp-zone-editor__actions">
        <button
          className={drawMode === "radius" ? "fp-zone-editor__mode-button is-active" : "fp-zone-editor__mode-button"}
          type="button"
          disabled={disabled}
          onClick={handleToggleRadiusMode}
        >
          {drawMode === "radius" ? "Рисовать контур" : "Скруглить линии"}
        </button>
        <button
          type="button"
          disabled={disabled}
          onClick={handleAddZone}
        >
          Новая зона
        </button>
        <button
          type="button"
          disabled={disabled || !selectedZone || selectedZone.points_norm_heatmap.length === 0}
          onClick={handleRemoveLastPoint}
        >
          Удалить точку
        </button>
        <button
          type="button"
          disabled={disabled || !selectedZone || selectedZone.points_norm_heatmap.length === 0}
          onClick={handleClearZone}
        >
          Очистить
        </button>
        <button
          className="fp-zone-editor__delete"
          type="button"
          disabled={disabled || !selectedZone}
          onClick={handleDeleteZone}
        >
          Удалить зону
        </button>
        {zones.length > 1 && (
          <div className="fp-zone-editor__zone-tabs">
            {zones.map((zone, index) => (
              <button
                key={zone.id ?? index}
                className={index === safeSelectedIndex ? "fp-zone-editor__zone-tab is-active" : "fp-zone-editor__zone-tab"}
                type="button"
                onClick={() => setSelectedIndex(index)}
              >
                {index + 1}
              </button>
            ))}
          </div>
        )}
      </div>
      <p className="fp-zone-editor__hint">
        {drawMode === "radius"
          ? "Потяните маркер на линии, чтобы добавить точку и скруглить контур FP-zone"
          : "Кликайте по изображению, чтобы добавить вершины FP-zone"}
      </p>
    </div>
  );
}

function createEmptyZone(points: FpZoneNorm["points_norm_heatmap"] = []): FpZoneNorm {
  return {
    id: createZoneId(),
    note: "Исключающая зона",
    points_norm_heatmap: points,
  };
}

function createZoneId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `fp-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function createEdgeHandles(points: InterestPointNorm[]) {
  if (points.length < 2) return [];
  const edgeCount = points.length >= 3 ? points.length : points.length - 1;

  return Array.from({ length: edgeCount }, (_, index) => {
    const start = points[index];
    const end = points[(index + 1) % points.length];
    return {
      insertIndex: index + 1,
      point: { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 },
    };
  });
}

function toSvgPoint(point: InterestPointNorm) {
  return `${point.x},${point.y}`;
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}
