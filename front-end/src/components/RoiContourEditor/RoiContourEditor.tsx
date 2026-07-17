import type { MouseEvent, PointerEvent } from "react";
import { useRef, useState } from "react";
import "./RoiContourEditor.css";

export type NormPoint = {
  x: number;
  y: number;
};

type RoiContourEditorProps = {
  imageUrl: string;
  points: NormPoint[];
  exclusionZones?: Array<{
    id?: string;
    points_norm_heatmap: NormPoint[];
  }>;
  disabled?: boolean;
  onChange: (points: NormPoint[]) => void;
};

export function RoiContourEditor({
  imageUrl,
  points,
  exclusionZones = [],
  disabled = false,
  onChange,
}: RoiContourEditorProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const dragRef = useRef<{ index: number; points: NormPoint[] } | null>(null);
  const ignoreNextClickRef = useRef(false);
  const [cursorPoint, setCursorPoint] = useState<NormPoint | null>(null);
  const svgPoints = points.map((point) => `${point.x},${point.y}`).join(" ");
  const edgeHandles = createEdgeHandles(points);

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled || ignoreNextClickRef.current || points.length >= 3) {
      ignoreNextClickRef.current = false;
      return;
    }

    const rect = imageRef.current?.getBoundingClientRect();

    if (!rect || rect.width <= 0 || rect.height <= 0) {
      return;
    }

    const nextPoint: NormPoint = {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };

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
    if (!drag || !point) return;

    const nextPoints = drag.points.map((currentPoint, index) => (index === drag.index ? point : currentPoint));
    dragRef.current = { ...drag, points: nextPoints };
    onChange(nextPoints);
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const handleRemoveLastPoint = () => {
    onChange(points.slice(0, -1));
  };

  const handleClear = () => {
    onChange([]);
  };

  const handleUseFullFrame = () => {
    onChange(createFullFramePolygon());
  };

  const actionButtons = [
    {
      title: "Удалить точку",
      onClick: handleRemoveLastPoint,
      disabled: disabled || points.length === 0,
    },
    {
      title: "Очистить",
      onClick: handleClear,
      disabled: disabled || points.length === 0,
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
        className={`roi-editor__canvas${disabled ? " roi-editor__canvas--disabled" : ""}`}
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
          {points.length >= 2 && <polyline points={svgPoints} />}
          {!disabled && cursorPoint && (
            <g className="roi-editor__cursor">
              <line x1={cursorPoint.x - 0.02} y1={cursorPoint.y} x2={cursorPoint.x + 0.02} y2={cursorPoint.y} />
              <line x1={cursorPoint.x} y1={cursorPoint.y - 0.02} x2={cursorPoint.x} y2={cursorPoint.y + 0.02} />
            </g>
          )}
          {edgeHandles.map((handle) => (
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
          {points.map((point, index) => (
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

      <div className="roi-editor__actions">
        {actionButtons.map((button) => (
          <button
            key={button.title}
            type="button"
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
