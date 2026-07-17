import type { MouseEvent, PointerEvent } from "react";
import { useRef, useState } from "react";
import { createCirclePolygonFromRadius, type NormPoint } from "./circleRoi";
import "./RoiContourEditor.css";

export type { NormPoint };

type DrawMode = "polygon" | "radius";

type RoiContourEditorProps = {
  imageUrl: string;
  points: NormPoint[];
  exclusionZones?: Array<{
    id?: string;
    points_norm_heatmap: NormPoint[];
  }>;
  disabled?: boolean;
  /** Режим «Радиус» (круг). Для joint ROI должен быть false. */
  allowRadiusMode?: boolean;
  onChange: (points: NormPoint[]) => void;
};

export function RoiContourEditor({
  imageUrl,
  points,
  exclusionZones = [],
  disabled = false,
  allowRadiusMode = true,
  onChange,
}: RoiContourEditorProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const [drawMode, setDrawMode] = useState<DrawMode>("polygon");
  const [radiusCenter, setRadiusCenter] = useState<NormPoint | null>(null);
  const [hoverPoint, setHoverPoint] = useState<NormPoint | null>(null);
  const effectiveDrawMode = allowRadiusMode ? drawMode : "polygon";
  const dragRef = useRef<{ index: number; points: NormPoint[] } | null>(null);
  const ignoreNextClickRef = useRef(false);
  const [cursorPoint, setCursorPoint] = useState<NormPoint | null>(null);
  const svgPoints = points.map((point) => `${point.x},${point.y}`).join(" ");
  const edgeHandles = createEdgeHandles(points);

  const resolveNormPoint = (event: MouseEvent<Element> | PointerEvent<Element>): NormPoint | null => {
    const rect = imageRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) {
      return null;
    }
    return {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };
  };

  const frameSize = () => {
    const image = imageRef.current;
    const width = image?.naturalWidth || image?.clientWidth || 1;
    const height = image?.naturalHeight || image?.clientHeight || 1;
    return { width, height };
  };

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled || ignoreNextClickRef.current || (effectiveDrawMode === "polygon" && points.length >= 3)) {
      ignoreNextClickRef.current = false;
      return;
    }

    const nextPoint = resolveNormPoint(event);
    if (!nextPoint) {
      return;
    }

    if (effectiveDrawMode === "radius") {
      if (!radiusCenter) {
        setRadiusCenter(nextPoint);
        setHoverPoint(nextPoint);
        return;
      }

      const { width, height } = frameSize();
      const circle = createCirclePolygonFromRadius(radiusCenter, nextPoint, width, height);
      setRadiusCenter(null);
      setHoverPoint(null);
      if (circle.length >= 3) {
        onChange(circle);
      }
      return;
    }

    onChange([...points, nextPoint]);
  };

  const getPointerPoint = (event: PointerEvent<Element>): NormPoint | null => {
    const rect = imageRef.current?.getBoundingClientRect();

    if (!rect || rect.width <= 0 || rect.height <= 0) {
      return null;
    }

    return {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };
  };

  const beginDrag = (event: PointerEvent<SVGCircleElement>, index: number, nextPoints = points) => {
    if (disabled) return;

    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { index, points: nextPoints };
    ignoreNextClickRef.current = true;

    if (nextPoints !== points) onChange(nextPoints);
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    const point = getPointerPoint(event);
    setCursorPoint(point);
    if (effectiveDrawMode === "radius" && radiusCenter && point) {
      setHoverPoint(point);
    }
    if (!drag || !point) return;

    const nextPoints = drag.points.map((currentPoint, index) => (index === drag.index ? point : currentPoint));
    dragRef.current = { ...drag, points: nextPoints };
    onChange(nextPoints);
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const handleRemoveLastPoint = () => {
    if (effectiveDrawMode === "radius" && radiusCenter) {
      setRadiusCenter(null);
      setHoverPoint(null);
      return;
    }
    onChange(points.slice(0, -1));
  };

  const handleClear = () => {
    setRadiusCenter(null);
    setHoverPoint(null);
    onChange([]);
  };

  const handleUseFullFrame = () => {
    setRadiusCenter(null);
    setHoverPoint(null);
    setDrawMode("polygon");
    onChange(createFullFramePolygon());
  };

  const handleToggleRadiusMode = () => {
    if (!allowRadiusMode) {
      return;
    }
    setRadiusCenter(null);
    setHoverPoint(null);
    setDrawMode((current) => (current === "radius" ? "polygon" : "radius"));
  };

  const draftCirclePoints =
    effectiveDrawMode === "radius" && radiusCenter && hoverPoint
      ? (() => {
          const { width, height } = frameSize();
          return createCirclePolygonFromRadius(radiusCenter, hoverPoint, width, height);
        })()
      : [];
  const draftCircleSvg = draftCirclePoints.map((point) => `${point.x},${point.y}`).join(" ");

  const actionButtons = [
    ...(allowRadiusMode
      ? [
          {
            title: effectiveDrawMode === "radius" ? "Полигон" : "Радиус",
            onClick: handleToggleRadiusMode,
            disabled,
            active: effectiveDrawMode === "radius",
          },
        ]
      : []),
    {
      title: "Удалить точку",
      onClick: handleRemoveLastPoint,
      disabled: disabled || (points.length === 0 && !(allowRadiusMode && radiusCenter)),
    },
    {
      title: "Очистить",
      onClick: handleClear,
      disabled: disabled || (points.length === 0 && !(allowRadiusMode && radiusCenter)),
    },
    {
      title: "Весь кадр",
      onClick: handleUseFullFrame,
      disabled,
    },
  ];

  return (
    <div className="roi-editor">
      <div
        className={`roi-editor__canvas${effectiveDrawMode === "radius" ? " roi-editor__canvas--radius" : ""}${disabled ? " roi-editor__canvas--disabled" : ""}`}
        onClick={handleCanvasClick}
        onPointerMove={handlePointerMove}
        onPointerLeave={() => {
          if (!dragRef.current) setCursorPoint(null);
        }}
      >
        <img
          ref={imageRef}
          src={imageUrl}
          alt="ROI"
        />
        <svg
          className="roi-editor__overlay"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        >
          {points.length >= 3 && <polygon points={svgPoints} />}
          {exclusionZones.map((zone, index) => {
            if (zone.points_norm_heatmap.length < 3) {
              return null;
            }

            const zonePoints = zone.points_norm_heatmap.map((point) => `${point.x},${point.y}`).join(" ");
            return (
              <polygon
                key={zone.id ?? index}
                className="roi-editor__exclusion-zone"
                points={zonePoints}
              />
            );
          })}
          {points.length >= 2 && effectiveDrawMode === "polygon" && <polyline points={svgPoints} />}
          {allowRadiusMode && draftCirclePoints.length >= 3 && (
            <polygon
              className="roi-editor__draft-circle"
              points={draftCircleSvg}
            />
          )}
          {allowRadiusMode && radiusCenter && hoverPoint && (
            <line
              className="roi-editor__radius-line"
              x1={radiusCenter.x}
              y1={radiusCenter.y}
              x2={hoverPoint.x}
              y2={hoverPoint.y}
            />
          )}
          {allowRadiusMode && radiusCenter && (
            <circle
              className="roi-editor__radius-center"
              cx={radiusCenter.x}
              cy={radiusCenter.y}
              r="0.014"
            />
          )}
          {!disabled && cursorPoint && (
            <g className="roi-editor__cursor">
              <line x1={cursorPoint.x - 0.02} y1={cursorPoint.y} x2={cursorPoint.x + 0.02} y2={cursorPoint.y} />
              <line x1={cursorPoint.x} y1={cursorPoint.y - 0.02} x2={cursorPoint.x} y2={cursorPoint.y + 0.02} />
            </g>
          )}
          {effectiveDrawMode === "polygon" && edgeHandles.map((handle) => (
            <circle
              key={`edge-${handle.insertIndex}`}
              className="roi-editor__edge-handle"
              cx={handle.point.x}
              cy={handle.point.y}
              r="0.015"
              onPointerDown={(event) => {
                const nextPoints = [...points];
                nextPoints.splice(handle.insertIndex, 0, handle.point);
                beginDrag(event, handle.insertIndex, nextPoints);
              }}
            />
          ))}
          {effectiveDrawMode === "polygon" && points.map((point, index) => (
            <circle
              key={`${point.x}-${point.y}-${index}`}
              className="roi-editor__vertex-handle"
              cx={point.x}
              cy={point.y}
              r="0.012"
              onPointerDown={(event) => beginDrag(event, index)}
            />
          ))}
        </svg>
      </div>

      <p className="roi-editor__hint">
        {allowRadiusMode && effectiveDrawMode === "radius"
          ? radiusCenter
            ? "Второй клик — конец радиуса (круг станет ROI-полигоном)"
            : "Режим радиуса: первый клик — центр круга"
          : "Режим полигона: клики добавляют вершины контура"}
      </p>

      <div className="roi-editor__actions">
        {actionButtons.map((button) => (
          <button
            key={button.title}
            type="button"
            className={button.active ? "roi-editor__actions-button--active" : undefined}
            disabled={button.disabled}
            onClick={button.onClick}
          >
            {button.title}
          </button>
        ))}
      </div>
    </div>
  );
}

function createFullFramePolygon(): NormPoint[] {
  return [
    { x: 0, y: 0 },
    { x: 1, y: 0 },
    { x: 1, y: 1 },
    { x: 0, y: 1 },
  ];
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}

function createEdgeHandles(points: NormPoint[]) {
  if (points.length < 2) return [];

  const edgeCount = points.length >= 3 ? points.length : points.length - 1;
  return Array.from({ length: edgeCount }, (_, index) => {
    const end = points[(index + 1) % points.length];
    const start = points[index];
    return {
      insertIndex: index + 1,
      point: { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 },
    };
  });
}
