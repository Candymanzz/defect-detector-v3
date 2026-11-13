import { useEffect, useRef, useState } from "react";
import type { PointerEvent } from "react";
import type { HeatmapDescriptor } from "../../shared/ws";
import { exclusionPointFromImage } from "../../shared/inspectionExclusions";
import type { ExclusionPoint, Exclusions } from "../../shared/inspectionExclusions";
import { drawGrayU8Heatmap, loadHeatmapForCamera } from "../HeatmapViewer/HeatmapController";
import { zoomExclusionView } from "./exclusionViewport";

type Props = {
  imageUrl: string;
  cameraId: number;
  heatmap?: HeatmapDescriptor | null;
  frameId?: string;
  currentFrame?: boolean;
  polygons: Exclusions;
  points: ExclusionPoint[] | null;
  selected: number | null;
  disabled: boolean;
  onImageReady: (url: string) => void;
  onAddPoint: (point: ExclusionPoint) => void;
  onSelect: (index: number) => void;
  onDragStart: (index: number) => boolean;
  onDrag: (point: ExclusionPoint) => void;
  onDragEnd: (cancel?: boolean) => void;
};

export function ExclusionImageEditor({ imageUrl, cameraId, heatmap, frameId, currentFrame, polygons, points, selected,
  disabled, onImageReady, onAddPoint, onSelect, onDragStart, onDrag, onDragEnd }: Props) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const dragRef = useRef<{ kind: "vertex" } | { kind: "pan"; x: number; y: number; panX: number; panY: number; moved: boolean } | null>(null);
  const ignoreClickRef = useRef(false);
  const [image, setImage] = useState({ url: "", width: 1, height: 1 });
  const ready = image.url === imageUrl;
  const [view, setView] = useState({ zoom: 1, x: 0, y: 0 });
  const [showHeatmap, setShowHeatmap] = useState(true);
  const [opacity, setOpacity] = useState(.7);
  const [heatmapState, setHeatmapState] = useState<{ source: HeatmapDescriptor; status: "loading" | "ready" | "error"; error?: string } | null>(null);
  const drawing = points !== null;
  const canPan = view.zoom > 1;
  const heatmapReady = heatmap && heatmapState?.source === heatmap && heatmapState.status === "ready";
  const activePoints = points ?? (selected === null ? [] : polygons[selected] ?? []);
  const svgPoints = (polygon: ExclusionPoint[]) => polygon.map(([x, y]) => `${x * (image.width - 1)},${y * (image.height - 1)}`).join(" ");
  useEffect(() => () => { onDragEnd(true); onImageReady(""); }, [onDragEnd, onImageReady]);

  useEffect(() => {
    const controller = new AbortController();
    const canvas = canvasRef.current;
    if (canvas) { canvas.width = 0; canvas.height = 0; }
    if (heatmap) {
      void loadHeatmapForCamera(heatmap, controller.signal).then(bytes => {
        if (controller.signal.aborted) return;
        drawGrayU8Heatmap(canvas, heatmap, bytes);
        setHeatmapState({ source: heatmap, status: "ready" });
      }).catch(error => {
        if (!controller.signal.aborted) setHeatmapState({ source: heatmap, status: "error", error: String(error) });
      });
    }
    return () => controller.abort();
  }, [heatmap, imageUrl, frameId]);

  const zoomTo = (next: number) => setView(current => zoomExclusionView(current, next,
    viewportRef.current?.clientWidth ?? 0, viewportRef.current?.clientHeight ?? 0));

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      setView(current => zoomExclusionView(current, current.zoom, viewport.clientWidth, viewport.clientHeight));
    });
    observer.observe(viewport);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      setView(current => {
        const rect = viewport.getBoundingClientRect();
        return zoomExclusionView(current, current.zoom * (event.deltaY < 0 ? 1.2 : 1 / 1.2), rect.width, rect.height,
          event.clientX - rect.left - rect.width / 2, event.clientY - rect.top - rect.height / 2);
      });
    };
    viewport.addEventListener("wheel", wheel, { passive: false });
    return () => viewport.removeEventListener("wheel", wheel);
  }, []);

  const resolvePoint = (event: PointerEvent | { clientX: number; clientY: number }) => {
    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect || !ready) return null;
    return exclusionPointFromImage(event.clientX, event.clientY, rect, image.width, image.height);
  };
  const endDrag = (cancel = false) => {
    const drag = dragRef.current;
    dragRef.current = null;
    if (drag?.kind === "vertex") onDragEnd(cancel);
    if (cancel) ignoreClickRef.current = false;
  };

  return <div className="exclusion-image-editor">
    <div className="inspection-exclusions__toolbar" role="group" aria-label="Масштаб и перемещение изображения">
      <button type="button" aria-label="Уменьшить изображение" disabled={view.zoom <= 1} onClick={() => zoomTo(view.zoom / 1.25)}>−</button>
      <output aria-label="Масштаб изображения">{Math.round(view.zoom * 100)}%</output>
      <button type="button" aria-label="Увеличить изображение" disabled={view.zoom >= 8} onClick={() => zoomTo(view.zoom * 1.25)}>+</button>
      <button type="button" onClick={() => setView({ zoom: 1, x: 0, y: 0 })}>Вписать</button>
    </div>
    <div className="inspection-exclusions__heatmap-controls">
      <label><input type="checkbox" checked={showHeatmap} disabled={!heatmap} onChange={event => setShowHeatmap(event.target.checked)} />Хитмап{frameId ? ` кадра ${frameId}` : ""}</label>
      <input aria-label="Непрозрачность хитмапа" type="range" min="0" max="1" step="0.05" value={opacity} disabled={!heatmap || !showHeatmap} onChange={event => setOpacity(Number(event.target.value))} />
    </div>
    <p className="inspection-exclusions__hint">
      {!heatmap ? "Для выбранного кадра хитмап ещё не получен. Нажмите «Проверить»; пока доступно изображение."
        : heatmapState?.source === heatmap && heatmapState.status === "error" ? `Не удалось загрузить хитмап. Доступно изображение. ${heatmapState.error}`
          : heatmapReady ? currentFrame ? "Текущий кадр с хитмапом выбранной инспекции." : "Эталон с хитмапом: зоны и отличия показаны в одних координатах."
            : "Загрузка хитмапа выбранного кадра…"}
    </p>
    <div ref={viewportRef} className="inspection-exclusions__viewport" data-pan={canPan} style={{ aspectRatio: ready ? `${image.width} / ${image.height}` : "4 / 3" }}
      onPointerDown={event => {
        ignoreClickRef.current = false;
        if (event.button !== 1 && !(event.button === 0 && canPan)) return;
        if (event.button === 1) event.preventDefault();
        dragRef.current = { kind: "pan", x: event.clientX, y: event.clientY, panX: view.x, panY: view.y, moved: false };
      }}
      onPointerMove={event => {
        const drag = dragRef.current;
        if (drag?.kind === "vertex") {
          const point = resolvePoint(event);
          if (point) onDrag(point);
        } else if (drag?.kind === "pan") {
          if (!drag.moved) {
            if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 4) return;
            drag.moved = true;
            ignoreClickRef.current = true;
            event.currentTarget.setPointerCapture(event.pointerId);
          }
          const rect = event.currentTarget.getBoundingClientRect();
          const maxX = rect.width * (view.zoom - 1) / 2, maxY = rect.height * (view.zoom - 1) / 2;
          setView(current => ({ ...current,
            x: Math.max(-maxX, Math.min(maxX, drag.panX + event.clientX - drag.x)),
            y: Math.max(-maxY, Math.min(maxY, drag.panY + event.clientY - drag.y)),
          }));
        }
      }}
      onPointerUp={() => endDrag()} onPointerCancel={() => endDrag(true)} onLostPointerCapture={() => endDrag()}
      onClickCapture={event => {
        if (ignoreClickRef.current) {
          ignoreClickRef.current = false;
          event.preventDefault();
          event.stopPropagation();
        }
      }}
      onClick={event => {
        if (!drawing || disabled) return;
        const point = resolvePoint(event);
        if (point) onAddPoint(point);
      }}>
      <div className="inspection-exclusions__image" style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.zoom})` }}>
        <img src={imageUrl} alt={`${currentFrame ? "Текущий кадр" : "Эталон"} камеры ${cameraId} с хитмапом для зон исключения`} draggable={false}
          onLoad={event => { setImage({ url: imageUrl, width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight }); onImageReady(imageUrl); }}
          onError={() => { setImage({ url: "", width: 1, height: 1 }); onImageReady(""); }} />
        <canvas ref={canvasRef} className="inspection-exclusions__heatmap" style={{ opacity: heatmapReady && showHeatmap ? opacity : 0 }} aria-hidden="true" />
        {ready && <svg ref={svgRef} viewBox={`0 0 ${image.width} ${image.height}`} preserveAspectRatio="xMidYMid meet" role="img"
          aria-label={drawing ? "Отметьте вершины замкнутой зоны" : "Контуры зон исключения"} data-drawing={drawing && !canPan}>
          {polygons.map((polygon, index) => <polygon key={index} points={svgPoints(polygon)} data-selected={selected === index}
            onClick={event => { if (!drawing && !disabled) { event.stopPropagation(); onSelect(index); } }} />)}
          {points !== null && (points.length >= 3
            ? <polygon className="inspection-exclusions__draft" points={svgPoints(points)} />
            : <polyline className="inspection-exclusions__draft" points={svgPoints(points)} />)}
          {activePoints.map(([x, y], index) => <circle key={index} className="inspection-exclusions__vertex"
            cx={x * (image.width - 1)} cy={y * (image.height - 1)} r={Math.max(image.width, image.height) / (65 * view.zoom)}
            onPointerDown={event => {
              if (disabled || event.button !== 0 || !onDragStart(index)) return;
              event.preventDefault(); event.stopPropagation(); event.currentTarget.setPointerCapture(event.pointerId);
              dragRef.current = { kind: "vertex" }; ignoreClickRef.current = true;
            }} />)}
        </svg>}
      </div>
    </div>
    <p className="inspection-exclusions__hint">Клик — добавить точку, потянуть вершину — изменить контур. При увеличении потяните изображение мышью, чтобы сдвинуть его. Масштаб: + / − или Ctrl + колесо.</p>
  </div>;
}
