import type { NormPoint } from "./circleRoi";

const MIN_AXIS_LEN = 1e-3;
const MIN_HALF_WIDTH = 1e-3;
const MIN_AREA = 1e-6;
const PARALLEL_DOT_MIN = 0.985;
/** |cos| of adjacent edges; above this ≈ слишком скошенный параллелограмм. */
const MAX_SKEW_DOT = 0.15;

/**
 * Ориентированный прямоугольник вокруг оси a→b: полоса шириной 2×halfWidthNorm.
 * Углы в порядке обхода (не самопересекающийся quad).
 */
export function createOrientedRectFromAxis(
  a: NormPoint,
  b: NormPoint,
  halfWidthNorm: number,
): NormPoint[] {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy);
  if (!(len >= MIN_AXIS_LEN) || !(halfWidthNorm >= MIN_HALF_WIDTH)) {
    return [];
  }

  const ux = dx / len;
  const uy = dy / len;
  const px = -uy * halfWidthNorm;
  const py = ux * halfWidthNorm;

  // Do not clamp corners individually — that would skew the rectangle near frame edges.
  return [
    { x: a.x + px, y: a.y + py },
    { x: b.x + px, y: b.y + py },
    { x: b.x - px, y: b.y - py },
    { x: a.x - px, y: a.y - py },
  ];
}

/** Восстановить ось и halfWidth из 4 углов ориентированного прямоугольника. */
export function axisFromOrientedRect(points: NormPoint[]): {
  a: NormPoint;
  b: NormPoint;
  halfWidth: number;
} | null {
  if (!isOrientedRectPolygon(points)) {
    return null;
  }
  const mid01 = midpoint(points[0], points[1]);
  const mid23 = midpoint(points[2], points[3]);
  const mid12 = midpoint(points[1], points[2]);
  const mid30 = midpoint(points[3], points[0]);
  const lenAlong01 = Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
  const lenAlong12 = Math.hypot(points[2].x - points[1].x, points[2].y - points[1].y);
  // Longer pair of opposite edges defines the seam axis.
  if (lenAlong01 >= lenAlong12) {
    return {
      a: mid30,
      b: mid12,
      halfWidth: lenAlong12 * 0.5,
    };
  }
  return {
    a: mid01,
    b: mid23,
    halfWidth: lenAlong01 * 0.5,
  };
}

function midpoint(a: NormPoint, b: NormPoint): NormPoint {
  return { x: (a.x + b.x) * 0.5, y: (a.y + b.y) * 0.5 };
}

/** Перпендикулярное расстояние от точки до бесконечной прямой через a–b. */
export function halfWidthFromPoint(a: NormPoint, b: NormPoint, point: NormPoint): number {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy);
  if (!(len >= MIN_AXIS_LEN)) {
    return 0;
  }
  return Math.abs((point.x - a.x) * dy - (point.y - a.y) * dx) / len;
}

/**
 * 4 вершины образуют ориентированный прямоугольник (параллелограмм с почти
 * параллельными противоположными сторонами и ненулевой площадью).
 */
export function isOrientedRectPolygon(points?: NormPoint[]): boolean {
  if (!points || points.length !== 4) {
    return false;
  }
  if (points.some((p) => !Number.isFinite(p.x) || !Number.isFinite(p.y))) {
    return false;
  }

  const area = Math.abs(polygonArea(points));
  if (!(area >= MIN_AREA)) {
    return false;
  }

  const edges = [
    edgeUnit(points[0], points[1]),
    edgeUnit(points[1], points[2]),
    edgeUnit(points[2], points[3]),
    edgeUnit(points[3], points[0]),
  ];
  if (edges.some((e) => e == null)) {
    return false;
  }

  // Opposite edges nearly parallel (same or opposite direction).
  const opp01 = Math.abs(dot(edges[0]!, edges[2]!));
  const opp12 = Math.abs(dot(edges[1]!, edges[3]!));
  if (opp01 < PARALLEL_DOT_MIN || opp12 < PARALLEL_DOT_MIN) {
    return false;
  }

  // Adjacent edges nearly perpendicular (rectangle, not skewed parallelogram).
  const adj = Math.abs(dot(edges[0]!, edges[1]!));
  if (adj > MAX_SKEW_DOT) {
    return false;
  }

  return true;
}

function edgeUnit(a: NormPoint, b: NormPoint): NormPoint | null {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const len = Math.hypot(dx, dy);
  if (!(len >= MIN_AXIS_LEN)) {
    return null;
  }
  return { x: dx / len, y: dy / len };
}

function polygonArea(points: NormPoint[]): number {
  let sum = 0;
  for (let i = 0; i < points.length; i += 1) {
    const cur = points[i];
    const next = points[(i + 1) % points.length];
    sum += cur.x * next.y - next.x * cur.y;
  }
  return sum * 0.5;
}

function dot(a: NormPoint, b: NormPoint): number {
  return a.x * b.x + a.y * b.y;
}
