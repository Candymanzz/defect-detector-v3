/// <reference lib="webworker" />

import { createNormalizationLut, HEATMAP_COLOR_LUT } from "./HeatmapColor";

type HeatmapRenderRequest = {
  requestId: number;
  buffer: ArrayBuffer;
  width: number;
  height: number;
};

type HeatmapRenderSuccess = {
  requestId: number;
  bitmap: ImageBitmap;
};

type HeatmapRenderFailure = {
  requestId: number;
  error: string;
};

const workerScope = self as DedicatedWorkerGlobalScope;

workerScope.onmessage = (event: MessageEvent<HeatmapRenderRequest>) => {
  const { requestId, buffer, width, height } = event.data;

  try {
    const expectedSize = width * height;
    const bytes = new Uint8Array(buffer);
    if (bytes.length < expectedSize) {
      throw new Error(`Invalid heatmap byte length: ${bytes.length}, expected ${expectedSize}`);
    }

    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext("2d");
    if (!context) {
      throw new Error("OffscreenCanvas 2D context is not available");
    }

    const imageData = context.createImageData(width, height);
    const normalizationLut = createNormalizationLut();
    const pixels = imageData.data;

    for (let index = 0; index < expectedSize; index += 1) {
      const colorIndex = normalizationLut[bytes[index]] * 4;
      const pixelIndex = index * 4;

      pixels[pixelIndex] = HEATMAP_COLOR_LUT[colorIndex];
      pixels[pixelIndex + 1] = HEATMAP_COLOR_LUT[colorIndex + 1];
      pixels[pixelIndex + 2] = HEATMAP_COLOR_LUT[colorIndex + 2];
      pixels[pixelIndex + 3] = HEATMAP_COLOR_LUT[colorIndex + 3];
    }

    context.putImageData(imageData, 0, 0);
    const bitmap = canvas.transferToImageBitmap();
    const response: HeatmapRenderSuccess = { requestId, bitmap };
    workerScope.postMessage(response, [bitmap]);
  } catch (error) {
    const response: HeatmapRenderFailure = {
      requestId,
      error: error instanceof Error ? error.message : "Не удалось отрисовать тепловую карту",
    };
    workerScope.postMessage(response);
  }
};

export {};
