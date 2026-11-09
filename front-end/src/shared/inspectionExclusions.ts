export type ExclusionPoint = [number, number];
export type ExclusionPolygon = ExclusionPoint[];
export type Exclusions = ExclusionPolygon[];

export type InspectionExclusions = {
  polygons: Exclusions;
  coordinate_space: "normalized";
  surface_skipped: boolean;
  remaining_area_work_px: number;
};

export function copyExclusions(zones: Exclusions): Exclusions {
  return zones.map((polygon) => polygon.map(([x, y]) => [x, y]));
}

/** Maps a click on the actual rendered image, excluding contain/letterbox margins. */
export function exclusionPointFromImage(
  clientX: number, clientY: number,
  rect: { left: number; top: number; width: number; height: number },
  width: number, height: number,
): ExclusionPoint | null {
  if (width < 2 || height < 2 || rect.width <= 0 || rect.height <= 0) return null;
  const scale = Math.min(rect.width / width, rect.height / height);
  const left = rect.left + (rect.width - width * scale) / 2;
  const top = rect.top + (rect.height - height * scale) / 2;
  const x = (clientX - left) / scale;
  const y = (clientY - top) / scale;
  if (x < 0 || y < 0 || x > width || y > height) return null;
  return [Math.min(1, x / (width - 1)), Math.min(1, y / (height - 1))];
}

function cross(a: ExclusionPoint, b: ExclusionPoint, c: ExclusionPoint) {
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
}

function onSegment(a: ExclusionPoint, b: ExclusionPoint, p: ExclusionPoint) {
  return Math.abs(cross(a, b, p)) <= 1e-12 &&
    p[0] >= Math.min(a[0], b[0]) && p[0] <= Math.max(a[0], b[0]) &&
    p[1] >= Math.min(a[1], b[1]) && p[1] <= Math.max(a[1], b[1]);
}

function intersects(a: ExclusionPoint, b: ExclusionPoint, c: ExclusionPoint, d: ExclusionPoint) {
  return (cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0) ||
    onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b);
}

export function validateExclusions(value: unknown): string | null {
  if (!Array.isArray(value) || value.length > 32) return "Допустимо не более 32 зон.";
  for (const polygon of value) {
    if (!Array.isArray(polygon) || polygon.length < 3 || polygon.length > 128) {
      return "В зоне должно быть от 3 до 128 точек.";
    }
    if (polygon.some((p: unknown) => !Array.isArray(p) || p.length !== 2 ||
      p.some((n: unknown) => typeof n !== "number" || !Number.isFinite(n) || n < 0 || n > 1))) {
      return "Координаты должны быть конечными числами от 0 до 1.";
    }
    const points = polygon as ExclusionPolygon;
    let area = 0;
    for (let i = 0; i < points.length; i++) {
      const a = points[i], b = points[(i + 1) % points.length];
      if (a[0] === b[0] && a[1] === b[1]) return "Уберите повторяющиеся соседние точки.";
      area += a[0] * b[1] - b[0] * a[1];
      for (let j = i + 1; j < points.length; j++) {
        if (j === i + 1 || (i === 0 && j === points.length - 1)) continue;
        if (intersects(a, b, points[j], points[(j + 1) % points.length])) {
          return "Контур зоны не должен пересекать или касаться самого себя.";
        }
      }
    }
    if (Math.abs(area) <= 1e-12) return "Зона должна иметь ненулевую площадь.";
  }
  return null;
}
