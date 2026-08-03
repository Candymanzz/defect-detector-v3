import type { FormEvent, MouseEvent, PointerEvent } from "react";
import { useEffect, useRef, useState } from "react";
import type { NormPoint } from "./circleRoi";
import {
  axisFromOrientedRect,
  createOrientedRectFromAxis,
  halfWidthFromPoint,
} from "./orientedRectRoi";
import "./RoiContourEditor.css";

export type { NormPoint };

type DrawMode = "polygon" | "radius";
export type RoiShapeMode = "polygon" | "oriented-rect";

type OrientedDraft =
  | { phase: "idle" }
  | { phase: "axis"; start: NormPoint; current: NormPoint }
  | { phase: "width"; a: NormPoint; b: NormPoint; halfWidth: number };

type RoiContourEditorProps = {
  imageUrl: string;
  points: NormPoint[];
  exclusionZones?: Array<{
    id?: string;
    points_norm_heatmap: NormPoint[];
  }>;
  disabled?: boolean;
  /** Разрешает режим скругления контура перетаскиванием точек на его линиях. */
  allowRadiusMode?: boolean;
  /** joint: только ориентированный прямоугольник по оси; interest: свободный полигон. */
  shapeMode?: RoiShapeMode;
  onChange: (points: NormPoint[]) => void;
};

const DEFAULT_HALF_WIDTH = 0.02;
const MIN_STRIP_WIDTH_PCT = 0.2;
const MAX_STRIP_WIDTH_PCT = 40;

export function RoiContourEditor({
  imageUrl,
  points,
  exclusionZones = [],
  disabled = false,
  allowRadiusMode = true,
  shapeMode = "polygon",
  onChange,
}: RoiContourEditorProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ index: number; points: NormPoint[] } | null>(null);
  const ignoreNextClickRef = useRef(false);
  const orientedPointerIdRef = useRef<number | null>(null);
  const widthInputFocusedRef = useRef(false);
  const [drawMode, setDrawMode] = useState<DrawMode>("polygon");
  const [cursorPoint, setCursorPoint] = useState<NormPoint | null>(null);
  const [orientedDraft, setOrientedDraft] = useState<OrientedDraft>({ phase: "idle" });
  const [widthInputText, setWidthInputText] = useState(() => formatStripWidthPct(DEFAULT_HALF_WIDTH * 2));
  const isOriented = shapeMode === "oriented-rect";
  const effectiveDrawMode = isOriented ? "polygon" : allowRadiusMode ? drawMode : "polygon";
  const svgPoints = points.map(toSvgPoint).join(" ");
  const edgeHandles = createEdgeHandles(points);
  const orientedPreview = buildOrientedPreview(orientedDraft);
  const activeHalfWidth =
    orientedDraft.phase === "width"
      ? orientedDraft.halfWidth
      : axisFromOrientedRect(points)?.halfWidth ?? DEFAULT_HALF_WIDTH;
  const canEditWidth =
    !disabled && isOriented && (orientedDraft.phase === "width" || Boolean(axisFromOrientedRect(points)));

  useEffect(() => {
    if (widthInputFocusedRef.current) return;
    setWidthInputText(formatStripWidthPct(activeHalfWidth * 2));
  }, [activeHalfWidth]);

  const resolveNormPoint = (event: MouseEvent<Element> | PointerEvent<Element>): NormPoint | null => {
    // Same box as the SVG overlay (canvas sized by the image).
    const rect = canvasRef.current?.getBoundingClientRect() ?? imageRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return null;

    return {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };
  };

  const commitOrientedRect = (a: NormPoint, b: NormPoint, halfWidth: number) => {
    const rect = createOrientedRectFromAxis(a, b, halfWidth);
    if (rect.length === 4) {
      onChange(rect);
      setOrientedDraft({ phase: "idle" });
    }
  };

  const applyTypedWidth = (raw: string): number | null => {
    const pct = Number.parseFloat(raw.replace(",", "."));
    if (!Number.isFinite(pct)) return null;
    const clampedPct = Math.min(MAX_STRIP_WIDTH_PCT, Math.max(MIN_STRIP_WIDTH_PCT, pct));
    const halfWidth = clampedPct / 200;
    setWidthInputText(formatStripWidthPct(clampedPct / 100));

    if (orientedDraft.phase === "width") {
      setOrientedDraft({ ...orientedDraft, halfWidth });
      return halfWidth;
    }

    const axis = axisFromOrientedRect(points);
    if (axis) {
      commitOrientedRect(axis.a, axis.b, halfWidth);
    }
    return halfWidth;
  };

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (ignoreNextClickRef.current) {
      ignoreNextClickRef.current = false;
      return;
    }
    if (disabled || isOriented || effectiveDrawMode === "radius") return;

    const nextPoint = resolveNormPoint(event);
    if (nextPoint) onChange([...points, nextPoint]);
  };

  const handleOrientedPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (disabled || !isOriented) return;
    if (event.button !== 0) return;

    const point = resolveNormPoint(event);
    if (!point) return;

    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    orientedPointerIdRef.current = event.pointerId;
    ignoreNextClickRef.current = true;

    if (orientedDraft.phase === "width") {
      commitOrientedRect(orientedDraft.a, orientedDraft.b, orientedDraft.halfWidth);
      return;
    }

    if (points.length > 0) {
      onChange([]);
    }
    setOrientedDraft({ phase: "axis", start: point, current: point });
  };

  const handleOrientedPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!isOriented) return;
    event.preventDefault();
    const point = resolveNormPoint(event);
    setCursorPoint(point);
    if (!point) return;

    setOrientedDraft((current) => {
      if (current.phase === "axis" && orientedPointerIdRef.current === event.pointerId) {
        return { ...current, current: point };
      }
      if (current.phase === "width" && !widthInputFocusedRef.current) {
        const halfWidth = Math.max(MIN_STRIP_WIDTH_PCT / 200, halfWidthFromPoint(current.a, current.b, point));
        return {
          ...current,
          halfWidth,
        };
      }
      return current;
    });
  };

  const handleOrientedPointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (!isOriented) return;
    if (orientedPointerIdRef.current !== event.pointerId) return;

    orientedPointerIdRef.current = null;
    try {
      event.currentTarget.releasePointerCapture(event.pointerId);
    } catch {
      // already released
    }

    setOrientedDraft((current) => {
      if (current.phase !== "axis") {
        return current;
      }
      const len = Math.hypot(current.current.x - current.start.x, current.current.y - current.start.y);
      if (len < 0.01) {
        return { phase: "idle" };
      }
      return {
        phase: "width",
        a: current.start,
        b: current.current,
        halfWidth: DEFAULT_HALF_WIDTH,
      };
    });
  };

  const beginDrag = (event: PointerEvent<SVGCircleElement>, index: number, nextPoints = points) => {
    if (disabled || isOriented || effectiveDrawMode !== "radius") return;

    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { index, points: nextPoints };
    ignoreNextClickRef.current = true;
    if (nextPoints !== points) onChange(nextPoints);
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (isOriented) {
      handleOrientedPointerMove(event);
      return;
    }

    const point = resolveNormPoint(event);
    setCursorPoint(point);

    const drag = dragRef.current;
    if (!drag || !point) return;

    const nextPoints = drag.points.map((currentPoint, index) => (index === drag.index ? point : currentPoint));
    dragRef.current = { ...drag, points: nextPoints };
    onChange(nextPoints);
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const handleClear = () => {
    setOrientedDraft({ phase: "idle" });
    setWidthInputText(formatStripWidthPct(DEFAULT_HALF_WIDTH * 2));
    onChange([]);
  };
  const handleUseFullFrame = () => {
    setDrawMode("polygon");
    onChange(createFullFramePolygon());
  };

  const handleWidthSubmit = (event: FormEvent) => {
    event.preventDefault();
    const halfWidth = applyTypedWidth(widthInputText);
    if (halfWidth == null || orientedDraft.phase !== "width") return;
    commitOrientedRect(orientedDraft.a, orientedDraft.b, halfWidth);
  };

  const actionButtons = [
    ...(allowRadiusMode && !isOriented
      ? [
          {
            title: effectiveDrawMode === "radius" ? "Рисовать контур" : "Задать радиус",
            onClick: () => setDrawMode((current) => (current === "radius" ? "polygon" : "radius")),
            disabled,
            active: effectiveDrawMode === "radius",
          },
        ]
      : []),
    ...(!isOriented
      ? [
          {
            title: "Удалить точку",
            onClick: () => onChange(points.slice(0, -1)),
            disabled: disabled || points.length === 0,
          },
        ]
      : []),
    {
      title: "Очистить",
      onClick: handleClear,
      disabled: disabled || (points.length === 0 && orientedDraft.phase === "idle"),
    },
    ...(!isOriented ? [{ title: "Весь кадр", onClick: handleUseFullFrame, disabled }] : []),
  ];

  const hint = isOriented
    ? orientedDraft.phase === "width"
      ? "Задайте ширину мышью или числом ниже, затем клик / «Готово»"
      : orientedDraft.phase === "axis"
        ? "Протяните ось вдоль шва"
        : "Протяните ось вдоль шва, затем задайте ширину полосы"
    : effectiveDrawMode === "radius"
      ? "Потяните маркер на линии, чтобы добавить точку и скруглить контур"
      : "Кликайте по изображению, чтобы добавить вершины контура";

  return (
    <div className="roi-editor">
      <div
        ref={canvasRef}
        className={`roi-editor__canvas${effectiveDrawMode === "radius" ? " roi-editor__canvas--radius" : ""}${isOriented ? " roi-editor__canvas--oriented" : ""}${disabled ? " roi-editor__canvas--disabled" : ""}`}
        onClick={handleCanvasClick}
        onPointerDown={isOriented ? handleOrientedPointerDown : undefined}
        onPointerMove={handlePointerMove}
        onPointerUp={isOriented ? handleOrientedPointerUp : undefined}
        onPointerCancel={isOriented ? handleOrientedPointerUp : undefined}
        onDragStart={(event) => event.preventDefault()}
        onPointerLeave={() => {
          if (!dragRef.current && orientedDraft.phase === "idle") setCursorPoint(null);
        }}
      >
        <img
          ref={imageRef}
          src={imageUrl}
          alt="ROI"
          draggable={false}
          onDragStart={(event) => event.preventDefault()}
        />
        <svg
          className="roi-editor__overlay"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        >
          {points.length >= 3 && <polygon points={svgPoints} />}
          {orientedPreview?.rect && orientedPreview.rect.length >= 3 && (
            <polygon className="roi-editor__oriented-preview" points={orientedPreview.rect.map(toSvgPoint).join(" ")} />
          )}
          {orientedPreview?.axis && (
            <line
              className="roi-editor__oriented-axis"
              x1={orientedPreview.axis.a.x}
              y1={orientedPreview.axis.a.y}
              x2={orientedPreview.axis.b.x}
              y2={orientedPreview.axis.b.y}
            />
          )}
          {exclusionZones.map((zone, index) =>
            zone.points_norm_heatmap.length >= 3 ? (
              <polygon
                key={zone.id ?? index}
                className="roi-editor__exclusion-zone"
                points={zone.points_norm_heatmap.map(toSvgPoint).join(" ")}
              />
            ) : null,
          )}
          {!isOriented && points.length >= 2 && <polyline points={svgPoints} />}
          {!disabled && cursorPoint && (
            <g className="roi-editor__cursor">
              <line x1={cursorPoint.x - 0.02} y1={cursorPoint.y} x2={cursorPoint.x + 0.02} y2={cursorPoint.y} />
              <line x1={cursorPoint.x} y1={cursorPoint.y - 0.02} x2={cursorPoint.x} y2={cursorPoint.y + 0.02} />
            </g>
          )}
          {!isOriented &&
            effectiveDrawMode === "radius" &&
            edgeHandles.map((handle) => (
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
          {!isOriented &&
            points.map((point, index) => (
              <circle
                key={`${point.x}-${point.y}-${index}`}
                className={`roi-editor__vertex-handle${effectiveDrawMode === "radius" ? " roi-editor__vertex-handle--draggable" : ""}`}
                cx={point.x}
                cy={point.y}
                r="0.012"
                onPointerDown={(event) => beginDrag(event, index)}
              />
            ))}
        </svg>
      </div>

      <p className="roi-editor__hint">{hint}</p>

      {isOriented && (
        <form className="roi-editor__width-row" onSubmit={handleWidthSubmit}>
          <label className="roi-editor__width-label" htmlFor="joint-strip-width">
            Ширина полосы, %
          </label>
          <input
            id="joint-strip-width"
            className="roi-editor__width-input"
            type="number"
            inputMode="decimal"
            min={MIN_STRIP_WIDTH_PCT}
            max={MAX_STRIP_WIDTH_PCT}
            step={0.1}
            value={widthInputText}
            disabled={!canEditWidth}
            onFocus={() => {
              widthInputFocusedRef.current = true;
            }}
            onBlur={() => {
              widthInputFocusedRef.current = false;
              applyTypedWidth(widthInputText);
            }}
            onChange={(event) => setWidthInputText(event.target.value)}
          />
          <button
            type="submit"
            className="roi-editor__width-apply"
            disabled={!canEditWidth || orientedDraft.phase !== "width"}
          >
            Готово
          </button>
        </form>
      )}

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

function buildOrientedPreview(draft: OrientedDraft): {
  axis?: { a: NormPoint; b: NormPoint };
  rect?: NormPoint[];
} | null {
  if (draft.phase === "axis") {
    return {
      axis: { a: draft.start, b: draft.current },
      rect: undefined,
    };
  }
  if (draft.phase === "width") {
    return {
      axis: { a: draft.a, b: draft.b },
      rect: createOrientedRectFromAxis(draft.a, draft.b, Math.max(draft.halfWidth, MIN_STRIP_WIDTH_PCT / 200)),
    };
  }
  return null;
}

function formatStripWidthPct(fullWidthNorm: number): string {
  return (Math.max(0, fullWidthNorm) * 100).toFixed(1);
}

function toSvgPoint(point: NormPoint) {
  return `${point.x},${point.y}`;
}

function createFullFramePolygon(): NormPoint[] {
  return [
    { x: 0, y: 0 },
    { x: 1, y: 0 },
    { x: 1, y: 1 },
    { x: 0, y: 1 },
  ];
}

function createEdgeHandles(points: NormPoint[]) {
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

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}
