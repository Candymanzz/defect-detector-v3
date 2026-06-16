import { useEffect, useRef, useState } from "react";
import type { HeatmapDescriptor } from "../../shared/ws";
import {
  clearHeatmapCanvas,
  drawGrayU8Heatmap,
  drawHeatmapBitmap,
  loadHeatmapForCamera,
} from "./HeatmapController";
import "./HeatmapViewer.css";

type HeatmapViewerProps = {
  cameraId: number;
  heatmap: HeatmapDescriptor | null;
  backgroundImageUrl?: string;
};
type HeatmapStatus = "idle" | "loading" | "ready" | "error";
type HeatmapWorkerResponse = {
  requestId: number;
  bitmap?: ImageBitmap;
  error?: string;
};

export function HeatmapViewer({ cameraId, heatmap, backgroundImageUrl }: HeatmapViewerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const requestIdRef = useRef(0);
  const [status, setStatus] = useState<HeatmapStatus>("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!heatmap) {
      clearHeatmapCanvas(canvasRef.current);
      return;
    }

    const controller = new AbortController();
    const currentHeatmap = heatmap;
    let worker: Worker | null = null;

    async function loadAndDrawHeatmap() {
      try {
        setStatus("loading");
        setError(null);
        const bytes = await loadHeatmapForCamera(currentHeatmap, controller.signal);

        if (controller.signal.aborted) {
          return;
        }

        if (canRenderInWorker()) {
          try {
            worker = createHeatmapWorker();
            const requestId = ++requestIdRef.current;
            const workerBuffer = bytes.buffer.slice(
              bytes.byteOffset,
              bytes.byteOffset + bytes.byteLength,
            );
            const bitmap = await renderHeatmapInWorker(
              worker,
              requestId,
              workerBuffer,
              currentHeatmap.width,
              currentHeatmap.height,
              controller.signal,
            );

            worker.terminate();
            worker = null;

            if (controller.signal.aborted || requestId !== requestIdRef.current) {
              bitmap.close();
              return;
            }
            drawHeatmapBitmap(
              canvasRef.current,
              bitmap,
              currentHeatmap.width,
              currentHeatmap.height,
            );
          } catch (workerError) {
            worker?.terminate();
            worker = null;

            if (
              controller.signal.aborted ||
              (workerError instanceof DOMException && workerError.name === "AbortError")
            ) {
              throw workerError;
            }
            drawGrayU8Heatmap(canvasRef.current, currentHeatmap, bytes);
          }
        } else {
          drawGrayU8Heatmap(canvasRef.current, currentHeatmap, bytes);
        }
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
      requestIdRef.current += 1;
      controller.abort();
      worker?.terminate();
      worker = null;
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
            alt=""
            decoding="async"
            fetchPriority="high"
            src={backgroundImageUrl}
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

function canRenderInWorker() {
  return typeof Worker !== "undefined" && typeof OffscreenCanvas !== "undefined";
}

function createHeatmapWorker() {
  return new Worker(new URL("./HeatmapRenderWorker.ts", import.meta.url), {
    type: "module",
  });
}

function renderHeatmapInWorker(
  worker: Worker,
  requestId: number,
  buffer: ArrayBuffer,
  width: number,
  height: number,
  signal: AbortSignal,
) {
  return new Promise<ImageBitmap>((resolve, reject) => {
    const handleMessage = (event: MessageEvent<HeatmapWorkerResponse>) => {
      if (event.data.requestId !== requestId) {
        event.data.bitmap?.close();
        return;
      }
      cleanup();
      if (event.data.bitmap) {
        resolve(event.data.bitmap);
      } else {
        reject(new Error(event.data.error ?? "Failed to render heatmap"));
      }
    };
    const handleError = () => {
      cleanup();
      reject(new Error("Heatmap worker failed"));
    };
    const handleAbort = () => {
      cleanup();
      reject(new DOMException("Heatmap rendering aborted", "AbortError"));
    };
    const cleanup = () => {
      worker.removeEventListener("message", handleMessage);
      worker.removeEventListener("error", handleError);
      signal.removeEventListener("abort", handleAbort);
    };

    if (signal.aborted) {
      handleAbort();
      return;
    }

    worker.addEventListener("message", handleMessage);
    worker.addEventListener("error", handleError);
    signal.addEventListener("abort", handleAbort, { once: true });

    try {
      worker.postMessage({ requestId, buffer, width, height }, [buffer]);
    } catch (error) {
      cleanup();
      reject(error);
    }
  });
}
