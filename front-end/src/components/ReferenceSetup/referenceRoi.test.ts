import { describe, expect, it } from "vitest";

import {
  createFullRoiPolygonNorm,
  createRoiFromPolygon,
  isValidRoiPolygon,
} from "../../components/ReferenceSetup/referenceRoi";

describe("referenceRoi", () => {
  it("creates full-frame normalized polygon", () => {
    const polygon = createFullRoiPolygonNorm(200, 100);

    expect(polygon).toHaveLength(4);
    expect(polygon[0]).toEqual({ x: 0, y: 0 });
    expect(polygon[2].x).toBe(1);
    expect(polygon[2].y).toBe(1);
  });

  it("builds pixel roi from normalized polygon", () => {
    const roi = createRoiFromPolygon(
      [
        { x: 0.1, y: 0.2 },
        { x: 0.8, y: 0.2 },
        { x: 0.8, y: 0.9 },
      ],
      100,
      80,
    );

    expect(roi.x).toBe(10);
    expect(roi.y).toBe(16);
    expect(roi.width).toBeGreaterThan(0);
    expect(roi.height).toBeGreaterThan(0);
  });

  it("validates polygon point count", () => {
    expect(isValidRoiPolygon([{ x: 0, y: 0 }, { x: 1, y: 0 }])).toBe(false);
    expect(
      isValidRoiPolygon([
        { x: 0, y: 0 },
        { x: 1, y: 0 },
        { x: 0.5, y: 1 },
      ]),
    ).toBe(true);
  });
});
