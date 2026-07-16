export type NormPoint = {
  x: number;
  y: number;
};

const DEFAULT_SEGMENTS = 64;

/**
 * Круг в пиксельном пространстве кадра: центр + конец радиуса в norm [0,1],
 * затем аппроксимация полигоном (совместимо с interest_polygon_norm).
 */
export function createCirclePolygonFromRadius(
  center: NormPoint,
  radiusEnd: NormPoint,
  frameWidth: number,
  frameHeight: number,
  segments: number = DEFAULT_SEGMENTS,
): NormPoint[] {
  const width = Math.max(1, frameWidth);
  const height = Math.max(1, frameHeight);
  const cxPx = center.x * (width - 1);
  const cyPx = center.y * (height - 1);
  const exPx = radiusEnd.x * (width - 1);
  const eyPx = radiusEnd.y * (height - 1);
  const radiusPx = Math.hypot(exPx - cxPx, eyPx - cyPx);
  if (!(radiusPx > 0.5)) {
    return [];
  }

  const count = Math.max(8, Math.floor(segments));
  const points: NormPoint[] = [];
  for (let i = 0; i < count; i += 1) {
    const angle = (Math.PI * 2 * i) / count;
    const xPx = cxPx + Math.cos(angle) * radiusPx;
    const yPx = cyPx + Math.sin(angle) * radiusPx;
    points.push({
      x: clamp01(xPx / (width - 1)),
      y: clamp01(yPx / (height - 1)),
    });
  }
  return points;
}

export function radiusLengthNorm(
  center: NormPoint,
  radiusEnd: NormPoint,
  frameWidth: number,
  frameHeight: number,
): number {
  const width = Math.max(1, frameWidth);
  const height = Math.max(1, frameHeight);
  const dx = (radiusEnd.x - center.x) * (width - 1);
  const dy = (radiusEnd.y - center.y) * (height - 1);
  return Math.hypot(dx, dy);
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}
