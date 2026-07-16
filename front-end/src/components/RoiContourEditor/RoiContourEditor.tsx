import type { MouseEvent } from "react";
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
  const svgPoints = points.map((point) => `${point.x},${point.y}`).join(" ");

  const resolveNormPoint = (event: MouseEvent<HTMLDivElement>): NormPoint | null => {
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
    if (disabled) {
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

  const handleCanvasMove = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled || effectiveDrawMode !== "radius" || !radiusCenter) {
      return;
    }
    const nextPoint = resolveNormPoint(event);
    if (nextPoint) {
      setHoverPoint(nextPoint);
    }
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
        className={
          effectiveDrawMode === "radius"
            ? "roi-editor__canvas roi-editor__canvas--radius"
            : "roi-editor__canvas"
        }
        onClick={handleCanvasClick}
        onMouseMove={handleCanvasMove}
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
          {points.map((point, index) => (
            <circle
              key={`${point.x}-${point.y}-${index}`}
              cx={point.x}
              cy={point.y}
              r="0.012"
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
