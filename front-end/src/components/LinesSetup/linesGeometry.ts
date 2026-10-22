/**
 * Геометрия редактора линий стыка/кромки. Точки здесь — пиксели кадра [x, y]; на сервер уходят доли кадра.
 * Сплайн Catmull-Rom совпадает с серверным (cppAnalisSurface: line_geometry.cpp), поэтому нарисованная
 * полоса поиска — ровно то, что измеряет детектор.
 */

export type Pt = [number, number];

/** Доли кадра <-> пиксели: сервер переводит точки как x * (width - 1). */
export function normToPx(p: Pt, width: number, height: number): Pt {
  return [p[0] * (width - 1), p[1] * (height - 1)];
}

export function pxToNorm(p: Pt, width: number, height: number): Pt {
  return [clamp01(p[0] / Math.max(1, width - 1)), clamp01(p[1] / Math.max(1, height - 1))];
}

function clamp01(v: number) {
  return Math.min(1, Math.max(0, v));
}

/** Участок между pts[s] и pts[s+1]; для двух точек это прямая. */
export function splineSegment(pts: readonly Pt[], s: number): Pt[] {
  const n = pts.length;
  const p0 = pts[s > 0 ? s - 1 : s];
  const p1 = pts[s];
  const p2 = pts[s + 1];
  const p3 = pts[s + 2 < n ? s + 2 : s + 1];
  const steps = Math.max(8, Math.round(Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / 4));
  const out: Pt[] = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const t2 = t * t;
    const t3 = t2 * t;
    const at = (c: 0 | 1) =>
      0.5 *
      (2 * p1[c] +
        (p2[c] - p0[c]) * t +
        (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2 +
        (3 * p1[c] - p0[c] - 3 * p2[c] + p3[c]) * t3);
    out.push([at(0), at(1)]);
  }
  return out;
}

/** Края полосы поиска на расстоянии `band` по нормали к линии. */
export function bandSide(curve: readonly Pt[], band: number, sign: 1 | -1): Pt[] {
  return curve.map((p, i) => {
    const a = curve[Math.max(i - 1, 0)];
    const b = curve[Math.min(i + 1, curve.length - 1)];
    const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
    return [p[0] - ((b[1] - a[1]) / len) * band * sign, p[1] + ((b[0] - a[0]) / len) * band * sign];
  });
}

export function pathData(points: readonly Pt[]): string {
  return points.map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join("");
}

export function segmentDistance(p: Pt, a: Pt, b: Pt): number {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const len2 = dx * dx + dy * dy;
  const t = len2 > 0 ? Math.min(1, Math.max(0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2)) : 0;
  return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
}

/** Ближайший к `p` участок линии (в пределах `radius`) или -1. */
export function nearestSegment(pts: readonly Pt[], p: Pt, radius: number): number {
  let best = -1;
  let bestDist = radius;
  for (let s = 0; s + 1 < pts.length; s++) {
    const curve = splineSegment(pts, s);
    for (let i = 0; i + 1 < curve.length; i++) {
      const d = segmentDistance(p, curve[i], curve[i + 1]);
      if (d < bestDist) {
        bestDist = d;
        best = s;
      }
    }
  }
  return best;
}

/** Точка линии, ближайшая к `p` (для вставки контрольной точки): участок и координаты. */
export function nearestOnLine(pts: readonly Pt[], p: Pt, radius: number): { segment: number; point: Pt } | null {
  let result: { segment: number; point: Pt } | null = null;
  let bestDist = radius;
  for (let s = 0; s + 1 < pts.length; s++) {
    for (const c of splineSegment(pts, s)) {
      const d = Math.hypot(p[0] - c[0], p[1] - c[1]);
      if (d < bestDist) {
        bestDist = d;
        result = { segment: s, point: c };
      }
    }
  }
  return result;
}

/** Номера исключённых участков после вставки точки в участок `segment`: обе половинки остаются исключёнными. */
export function skipAfterInsert(skip: readonly number[], segment: number): number[] {
  return skip.flatMap((x) => (x < segment ? [x] : x === segment ? [segment, segment + 1] : [x + 1]));
}

/**
 * Вершины ROI, выбранные пользователем, по порядку полигона; ряд начинается после самого большого разрыва между
 * выбранными номерами — линия не стартует из середины выбранного куска.
 */
export function orderedRoiPick(selected: readonly number[], count: number): number[] {
  const a = [...selected].sort((x, y) => x - y);
  if (a.length < 2) return a;
  let best = 0;
  let bestGap = -1;
  a.forEach((v, i) => {
    const gap = (a[(i + 1) % a.length] - v + count) % count || count;
    if (gap > bestGap) {
      bestGap = gap;
      best = (i + 1) % a.length;
    }
  });
  return a.slice(best).concat(a.slice(0, best));
}
