import type { HeatmapDescriptor, PixelRoi } from "./ws";

// Bounds include every nonzero heatmap pixel; padding is in source-frame pixels.
export function learnedRegionBounds(bytes: Uint8Array, heatmap: Pick<HeatmapDescriptor, "width" | "height">,
  frameWidth: number, frameHeight: number, padding = 10): PixelRoi {
  const { width, height } = heatmap;
  if (width <= 0 || height <= 0 || bytes.length !== width * height || frameWidth <= 0 || frameHeight <= 0) {
    throw new Error("Некорректные размеры карты или кадра");
  }
  let left = width, top = height, right = -1, bottom = -1;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    if (bytes[y * width + x] === 0) continue;
    left = Math.min(left, x); top = Math.min(top, y);
    right = Math.max(right, x); bottom = Math.max(bottom, y);
  }
  if (right < 0) throw new Error("На карте нет аномалий для дообучения");
  const x = Math.max(0, Math.floor(left * frameWidth / width) - padding);
  const y = Math.max(0, Math.floor(top * frameHeight / height) - padding);
  return { x, y,
    width: Math.min(frameWidth, Math.ceil((right + 1) * frameWidth / width) + padding) - x,
    height: Math.min(frameHeight, Math.ceil((bottom + 1) * frameHeight / height) + padding) - y };
}

export async function loadLearningFrame(url: string) {
  const image = new Image();
  image.crossOrigin = "anonymous";
  image.src = url;
  await image.decode();
  return image;
}

export async function cropLearningFrame(image: HTMLImageElement, bounds: PixelRoi): Promise<Blob> {
  const canvas = document.createElement("canvas");
  canvas.width = bounds.width; canvas.height = bounds.height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Не удалось вырезать фрагмент кадра");
  context.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, 0, 0, bounds.width, bounds.height);
  return new Promise((resolve, reject) => canvas.toBlob(
    (blob) => blob ? resolve(blob) : reject(new Error("Не удалось сохранить фрагмент")), "image/png"));
}
