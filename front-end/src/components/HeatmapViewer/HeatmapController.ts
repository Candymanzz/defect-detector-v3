import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { HeatmapDescriptor } from "../../shared/ws";
import { createNormalizationLut, HEATMAP_COLOR_LUT } from "./HeatmapColor";

export async function loadHeatmapForCamera(heatmap: HeatmapDescriptor, signal?: AbortSignal) {
  validateHeatmap(heatmap);

  const buffer = await loadHeatmapBuffer(heatmap, signal);
  return new Uint8Array(buffer);
}

export function clearHeatmapCanvas(canvas: HTMLCanvasElement | null) {
  if (!canvas) {
    return;
  }

  const width = canvas.width;
  canvas.width = 0;
  canvas.width = width;
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
  const normalizationLut = createNormalizationLut(bytes, expectedSize);
  const pixels = imageData.data;

  for (let index = 0; index < expectedSize; index += 1) {
    const colorIndex = normalizationLut[bytes[index]] * 4;
    const pixelIndex = index * 4;

    pixels[pixelIndex] = HEATMAP_COLOR_LUT[colorIndex];
    pixels[pixelIndex + 1] = HEATMAP_COLOR_LUT[colorIndex + 1];
    pixels[pixelIndex + 2] = HEATMAP_COLOR_LUT[colorIndex + 2];
    pixels[pixelIndex + 3] = HEATMAP_COLOR_LUT[colorIndex + 3];
  }

  ctx.putImageData(imageData, 0, 0);
}

export function drawHeatmapBitmap(
  canvas: HTMLCanvasElement | null,
  bitmap: ImageBitmap,
  width: number,
  height: number,
) {
  if (!canvas) {
    bitmap.close();
    return;
  }

  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) {
    bitmap.close();
    throw new Error("Canvas context is not available");
  }
  context.clearRect(0, 0, width, height);
  context.drawImage(bitmap, 0, 0);
  bitmap.close();
}
