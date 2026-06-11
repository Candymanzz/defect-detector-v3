import { useEffect, useRef, useState } from "react";
import type { HeatmapDescriptor } from "../../shared/ws";
import { clearHeatmapCanvas, drawGrayU8Heatmap, loadHeatmapForCamera } from "./HeatmapController";
import "./HeatmapViewer.css";

type HeatmapViewerProps = {
  cameraId: number;
  heatmap: HeatmapDescriptor | null;
  backgroundImageUrl?: string;
};
type HeatmapStatus = "idle" | "loading" | "ready" | "error";
export function HeatmapViewer({ cameraId, heatmap, backgroundImageUrl }: HeatmapViewerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [status, setStatus] = useState<HeatmapStatus>("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!heatmap) {
      clearHeatmapCanvas(canvasRef.current);
      return;
    }

    const controller = new AbortController();
    const currentHeatmap = heatmap;

    async function loadAndDrawHeatmap() {
      try {
        setStatus("loading");
        setError(null);
        const bytes = await loadHeatmapForCamera(currentHeatmap, controller.signal);

        if (controller.signal.aborted) {
          return;
        }

        drawGrayU8Heatmap(canvasRef.current, currentHeatmap, bytes);
        setStatus("ready");
      } catch (nextError) {
        if (controller.signal.aborted || (nextError instanceof DOMException && nextError.name === "AbortError")) {
          return;
        }

        clearHeatmapCanvas(canvasRef.current);
        setStatus("error");
        setError(nextError instanceof Error ? nextError.message : "Failed to render heatmap");
      }
    }

    loadAndDrawHeatmap();

    return () => {
      controller.abort();
    };
  }, [cameraId, heatmap]);

  if (!heatmap) {
    return <div className="heatmap-viewer__empty">No heatmap</div>;
  }

  return (
    <figure className="heatmap-viewer">
      <div
        className="heatmap-viewer__canvas-wrap"
        style={{ aspectRatio: `${heatmap.width} / ${heatmap.height}` }}
      >
        {backgroundImageUrl && (
          <img
            className="heatmap-viewer__image"
            src={backgroundImageUrl}
            alt=""
          />
        )}

        <canvas
          ref={canvasRef}
          className="heatmap-viewer__canvas"
        />

        {status === "loading" && <div className="heatmap-viewer__status">Loading heatmap</div>}
      </div>

      <figcaption>
        Heatmap {heatmap.width}x{heatmap.height}
        {error && <span className="heatmap-viewer__error"> {error}</span>}
      </figcaption>
    </figure>
  );
}
