import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { HeatmapDescriptor } from "../../shared/ws";

export async function loadHeatmapForCamera(heatmap: HeatmapDescriptor, signal?: AbortSignal) {
  validateHeatmap(heatmap);

  const buffer = await loadHeatmapBuffer(heatmap, signal);
  return new Uint8Array(buffer);
}

export function clearHeatmapCanvas(canvas: HTMLCanvasElement | null) {
  const ctx = canvas?.getContext("2d");

  if (!canvas || !ctx) {
    return;
  }

  ctx.clearRect(0, 0, canvas.width, canvas.height);
}

async function loadHeatmapBuffer(heatmap: HeatmapDescriptor, signal?: AbortSignal) {
  if (heatmap.http_path) {
    const response = await fetch(orchestratorApi.url(heatmap.http_path), {
      headers: {
        Accept: "application/octet-stream",
      },
      signal,
    });

    if (!response.ok) {
      throw new Error(`Failed to load heatmap: HTTP ${response.status}`);
    }

    return response.arrayBuffer();
  }

  if (heatmap.artifact_id) {
    return orchestratorApi.getHeatmapArtifact(heatmap.artifact_id, signal);
  }

  throw new Error("Heatmap source is missing for the selected inspect result");
}

function validateHeatmap(heatmap: HeatmapDescriptor) {
  if (heatmap.pixel_format !== "gray_u8") {
    throw new Error(`Unsupported heatmap format: ${heatmap.pixel_format}`);
  }

  if (heatmap.channels !== 1) {
    throw new Error(`Unsupported heatmap channels: ${heatmap.channels}`);
  }

  if (heatmap.width <= 0 || heatmap.height <= 0) {
    throw new Error(`Invalid heatmap size: ${heatmap.width}x${heatmap.height}`);
  }
}

export function drawGrayU8Heatmap(canvas: HTMLCanvasElement | null, heatmap: HeatmapDescriptor, bytes: Uint8Array) {
  if (!canvas) {
    return;
  }

  const expectedSize = heatmap.width * heatmap.height;

  if (bytes.length < expectedSize) {
    throw new Error(`Invalid heatmap byte length: ${bytes.length}, expected ${expectedSize}`);
  }

  canvas.width = heatmap.width;
  canvas.height = heatmap.height;

  const ctx = canvas.getContext("2d");

  if (!ctx) {
    throw new Error("Canvas context is not available");
  }

  const imageData = ctx.createImageData(heatmap.width, heatmap.height);
  const values = normalizeHeatmapBytes(bytes, expectedSize);

  for (let index = 0; index < expectedSize; index += 1) {
    const value = values[index];
    const pixelIndex = index * 4;
    const color = heatmapColor(value);

    imageData.data[pixelIndex] = color.r;
    imageData.data[pixelIndex + 1] = color.g;
    imageData.data[pixelIndex + 2] = color.b;
    imageData.data[pixelIndex + 3] = color.a;
  }

  ctx.putImageData(imageData, 0, 0);
}

function normalizeHeatmapBytes(bytes: Uint8Array, size: number) {
  let min = 255;
  let max = 0;

  for (let index = 0; index < size; index += 1) {
    const value = bytes[index];
    min = Math.min(min, value);
    max = Math.max(max, value);
  }

  if (max <= min) {
    return new Uint8Array(size);
  }

  const normalized = new Uint8Array(size);
  const range = max - min;

  for (let index = 0; index < size; index += 1) {
    const ratio = (bytes[index] - min) / range;
    normalized[index] = Math.round(Math.pow(ratio, 0.8) * 255);
  }

  return normalized;
}

function heatmapColor(value: number) {
  const ratio = Math.min(1, Math.max(0, value / 255));
  const color = jetHeatmapColor(ratio);

  return {
    ...color,
    a: Math.round(95 + 110 * ratio),
  };
}

function jetHeatmapColor(ratio: number) {
  const stops = [
    { at: 0, r: 0, g: 0, b: 150 },
    { at: 0.2, r: 0, g: 95, b: 255 },
    { at: 0.42, r: 0, g: 255, b: 255 },
    { at: 0.62, r: 80, g: 255, b: 80 },
    { at: 0.78, r: 255, g: 235, b: 0 },
    { at: 0.9, r: 255, g: 120, b: 0 },
    { at: 1, r: 190, g: 0, b: 0 },
  ];

  const nextStopIndex = stops.findIndex((stop) => ratio <= stop.at);
  const nextStop = stops[Math.max(1, nextStopIndex === -1 ? stops.length - 1 : nextStopIndex)];
  const prevStop = stops[stops.indexOf(nextStop) - 1];
  const localRatio = (ratio - prevStop.at) / (nextStop.at - prevStop.at);

  return {
    r: interpolateChannel(prevStop.r, nextStop.r, localRatio),
    g: interpolateChannel(prevStop.g, nextStop.g, localRatio),
    b: interpolateChannel(prevStop.b, nextStop.b, localRatio),
  };
}

function interpolateChannel(from: number, to: number, ratio: number) {
  return Math.round(from + (to - from) * ratio);
}
