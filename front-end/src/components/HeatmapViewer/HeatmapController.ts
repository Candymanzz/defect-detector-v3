import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { ExcludedNormalZone, HeatmapDescriptor } from "../../shared/ws";
import { createNormalizationLut, HEATMAP_COLOR_LUT } from "./HeatmapColor";

const HEATMAP_CACHE_LIMIT = 12;
const heatmapBufferCache = new Map<string, ArrayBuffer>();

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
  const cacheKey = resolveHeatmapCacheKey(heatmap);
  if (cacheKey) {
    const cached = heatmapBufferCache.get(cacheKey);
    if (cached) {
      heatmapBufferCache.delete(cacheKey);
      heatmapBufferCache.set(cacheKey, cached);
      return cached;
    }
  }

  let buffer: ArrayBuffer;
  if (heatmap.http_path) {
    const response = await fetch(resolveHeatmapUrl(heatmap.http_path), {
      headers: {
        Accept: "application/octet-stream",
      },
      signal,
    });

    if (!response.ok) {
      throw new Error(`Не удалось загрузить тепловую карту: HTTP ${response.status}`);
    }

    buffer = await response.arrayBuffer();
  } else if (heatmap.artifact_id) {
    buffer = await orchestratorApi.getHeatmapArtifact(heatmap.artifact_id, signal);
  } else {
    throw new Error("Источник тепловой карты отсутствует для выбранного результата инспекции");
  }

  if (cacheKey) {
    rememberHeatmapBuffer(cacheKey, buffer);
  }

  return buffer;
}

function validateHeatmap(heatmap: HeatmapDescriptor) {
  if (heatmap.pixel_format !== "gray_u8") {
    throw new Error(`Неподдерживаемый формат тепловой карты: ${heatmap.pixel_format}`);
  }

  if (heatmap.channels !== 1) {
    throw new Error(`Неподдерживаемое число каналов тепловой карты: ${heatmap.channels}`);
  }

  if (heatmap.width <= 0 || heatmap.height <= 0) {
    throw new Error(`Некорректный размер тепловой карты: ${heatmap.width}x${heatmap.height}`);
  }
}

export function drawGrayU8Heatmap(canvas: HTMLCanvasElement | null, heatmap: HeatmapDescriptor, bytes: Uint8Array) {
  if (!canvas) {
    return;
  }

  const expectedSize = heatmap.width * heatmap.height;

  if (bytes.length < expectedSize) {
    throw new Error(`Некорректная длина данных тепловой карты: ${bytes.length}, ожидалось ${expectedSize}`);
  }

  canvas.width = heatmap.width;
  canvas.height = heatmap.height;

  const ctx = canvas.getContext("2d");

  if (!ctx) {
    throw new Error("Контекст canvas недоступен");
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
    throw new Error("Контекст canvas недоступен");
  }
  context.clearRect(0, 0, width, height);
  context.drawImage(bitmap, 0, 0);
  bitmap.close();
}

export function drawExcludedNormalZones(
  canvas: HTMLCanvasElement | null,
  heatmap: HeatmapDescriptor,
  zones: readonly ExcludedNormalZone[],
) {
  if (!canvas || zones.length === 0) {
    return;
  }

  const context = canvas.getContext("2d");
  if (!context) {
    return;
  }

  context.save();
  context.lineCap = "round";
  context.lineJoin = "round";
  for (const zone of zones) {
    if (zone.excluded_from_score !== true) {
      continue;
    }
    const points = pixelPolygon(zone, heatmap.width, heatmap.height)
      ?? normalizedPolygon(zone.polygon, heatmap.width, heatmap.height);
    if (points.length < 3) {
      continue;
    }

    context.save();
    drawPolygon(context, points);
    context.clip();
    context.strokeStyle = "rgba(185, 75, 210, 0.72)";
    context.lineWidth = 2;
    const spacing = 18;
    for (let offset = -heatmap.height; offset < heatmap.width; offset += spacing) {
      context.beginPath();
      context.moveTo(offset, 0);
      context.lineTo(offset + heatmap.height, heatmap.height);
      context.stroke();
    }
    context.restore();

    drawPolygon(context, points);
    context.strokeStyle = "rgb(185, 75, 210)";
    context.lineWidth = 5;
    context.stroke();
  }
  context.restore();
}

function pixelPolygon(
  zone: ExcludedNormalZone,
  width: number,
  height: number,
): Array<[number, number]> | null {
  const polygon = zone.polygon_px;
  const sourceWidth = Number(zone.coordinate_width);
  const sourceHeight = Number(zone.coordinate_height);
  if (!polygon || polygon.length < 3 || sourceWidth <= 0 || sourceHeight <= 0) {
    return null;
  }

  const points: Array<[number, number]> = [];
  for (const point of polygon) {
    const x = Array.isArray(point) ? point[0] : point.x;
    const y = Array.isArray(point) ? point[1] : point.y;
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      continue;
    }
    points.push([
      Math.round(
        (Math.min(sourceWidth - 1, Math.max(0, x)) * (width - 1)) /
          Math.max(1, sourceWidth - 1),
      ),
      Math.round(
        (Math.min(sourceHeight - 1, Math.max(0, y)) * (height - 1)) /
          Math.max(1, sourceHeight - 1),
      ),
    ]);
  }
  return points.length >= 3 ? points : null;
}

function normalizedPolygon(
  polygon: ExcludedNormalZone["polygon"],
  width: number,
  height: number,
): Array<[number, number]> {
  const points: Array<[number, number]> = [];
  for (const point of polygon) {
    const x = Array.isArray(point) ? point[0] : point.x;
    const y = Array.isArray(point) ? point[1] : point.y;
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      continue;
    }
    points.push([
      Math.round(Math.min(1, Math.max(0, x)) * (width - 1)),
      Math.round(Math.min(1, Math.max(0, y)) * (height - 1)),
    ]);
  }
  return points;
}

function drawPolygon(context: CanvasRenderingContext2D, points: Array<[number, number]>) {
  context.beginPath();
  context.moveTo(points[0][0], points[0][1]);
  for (let index = 1; index < points.length; index += 1) {
    context.lineTo(points[index][0], points[index][1]);
  }
  context.closePath();
}

function resolveHeatmapUrl(path: string) {
  return path.startsWith("blob:") ? path : orchestratorApi.url(path);
}

function resolveHeatmapCacheKey(heatmap: HeatmapDescriptor) {
  if (heatmap.artifact_id) {
    return `artifact:${heatmap.artifact_id}`;
  }
  return null;
}

function rememberHeatmapBuffer(cacheKey: string, buffer: ArrayBuffer) {
  heatmapBufferCache.delete(cacheKey);
  heatmapBufferCache.set(cacheKey, buffer);
  while (heatmapBufferCache.size > HEATMAP_CACHE_LIMIT) {
    const oldestKey = heatmapBufferCache.keys().next().value;
    if (!oldestKey) {
      break;
    }
    heatmapBufferCache.delete(oldestKey);
  }
}
