import type { InterestPointNorm, PixelRoi, PreviewFramePayload } from "../../shared/ws";
import { isOrientedRectPolygon } from "../RoiContourEditor/orientedRectRoi";

export function createFullRoi(frame: PreviewFramePayload): PixelRoi {
  return {
    x: 0,
    y: 0,
    width: frame.current.width,
    height: frame.current.height,
  };
}

export function createFullRoiPolygonNorm(frameWidth: number, frameHeight: number): InterestPointNorm[] {
  const left = normalizeRoiCoordinate(0, frameWidth);
  const top = normalizeRoiCoordinate(0, frameHeight);
  const right = normalizeRoiCoordinate(frameWidth, frameWidth);
  const bottom = normalizeRoiCoordinate(frameHeight, frameHeight);

  return [
    { x: left, y: top },
    { x: right, y: top },
    { x: right, y: bottom },
    { x: left, y: bottom },
  ];
}

export function createRoiFromPolygon(
  points: InterestPointNorm[],
  frameWidth: number,
  frameHeight: number,
): PixelRoi {
  const xs = points.map((point) => point.x * frameWidth);
  const ys = points.map((point) => point.y * frameHeight);
  const left = Math.max(0, Math.floor(Math.min(...xs)));
  const top = Math.max(0, Math.floor(Math.min(...ys)));
  const right = Math.min(frameWidth, Math.ceil(Math.max(...xs)));
  const bottom = Math.min(frameHeight, Math.ceil(Math.max(...ys)));

  return {
    x: left,
    y: top,
    width: Math.max(1, right - left),
    height: Math.max(1, bottom - top),
  };
}

export function isValidRoiPolygon(points?: InterestPointNorm[]) {
  return Boolean(points && points.length >= 3);
}

/** Joint ROI: ровно 4 угла ориентированного прямоугольника (полоса вдоль шва). */
export function isValidJointRoiPolygon(points?: InterestPointNorm[]) {
  return Boolean(points && isOrientedRectPolygon(points));
}

function normalizeRoiCoordinate(value: number, size: number) {
  if (!Number.isFinite(value) || !Number.isFinite(size) || size <= 0) {
    return 0;
  }

  return Math.min(1, Math.max(0, value / size));
}
