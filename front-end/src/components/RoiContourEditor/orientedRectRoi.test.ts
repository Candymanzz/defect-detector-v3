import { describe, expect, it } from "vitest";

import {
  axisFromOrientedRect,
  createOrientedRectFromAxis,
  halfWidthFromPoint,
  isOrientedRectPolygon,
} from "./orientedRectRoi";

describe("orientedRectRoi", () => {
  it("builds horizontal strip from axis and half-width", () => {
    const rect = createOrientedRectFromAxis({ x: 0.2, y: 0.5 }, { x: 0.8, y: 0.5 }, 0.05);

    expect(rect).toHaveLength(4);
    expect(isOrientedRectPolygon(rect)).toBe(true);
    expect(rect[0].y).toBeCloseTo(0.55, 5);
    expect(rect[1].y).toBeCloseTo(0.55, 5);
    expect(rect[2].y).toBeCloseTo(0.45, 5);
    expect(rect[3].y).toBeCloseTo(0.45, 5);
  });

  it("builds angled strip", () => {
    const a = { x: 0.3, y: 0.3 };
    const b = { x: 0.7, y: 0.7 };
    const rect = createOrientedRectFromAxis(a, b, 0.04);

    expect(rect).toHaveLength(4);
    expect(isOrientedRectPolygon(rect)).toBe(true);
    expect(halfWidthFromPoint(a, b, rect[0])).toBeCloseTo(0.04, 5);
    expect(halfWidthFromPoint(a, b, rect[2])).toBeCloseTo(0.04, 5);
  });

  it("returns empty when axis or width is too small", () => {
    expect(createOrientedRectFromAxis({ x: 0.5, y: 0.5 }, { x: 0.5, y: 0.5 }, 0.05)).toEqual([]);
    expect(createOrientedRectFromAxis({ x: 0.2, y: 0.5 }, { x: 0.8, y: 0.5 }, 0)).toEqual([]);
  });

  it("rejects non-rectangle polygons", () => {
    expect(isOrientedRectPolygon([{ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 0.5, y: 1 }])).toBe(false);
    expect(
      isOrientedRectPolygon([
        { x: 0, y: 0 },
        { x: 1, y: 0 },
        { x: 0.8, y: 0.5 },
        { x: 0.2, y: 0.9 },
      ]),
    ).toBe(false);
  });

  it("accepts axis-aligned rectangle", () => {
    expect(
      isOrientedRectPolygon([
        { x: 0.1, y: 0.1 },
        { x: 0.9, y: 0.1 },
        { x: 0.9, y: 0.4 },
        { x: 0.1, y: 0.4 },
      ]),
    ).toBe(true);
  });

  it("recovers axis and half-width from oriented rect", () => {
    const a = { x: 0.2, y: 0.5 };
    const b = { x: 0.8, y: 0.5 };
    const halfWidth = 0.04;
    const rect = createOrientedRectFromAxis(a, b, halfWidth);
    const recovered = axisFromOrientedRect(rect);

    expect(recovered).not.toBeNull();
    expect(recovered!.halfWidth).toBeCloseTo(halfWidth, 5);
    expect(recovered!.a.y).toBeCloseTo(0.5, 5);
    expect(recovered!.b.y).toBeCloseTo(0.5, 5);
  });
});
