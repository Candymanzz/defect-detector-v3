import { describe, expect, it } from "vitest";
import { createCirclePolygonFromRadius, radiusLengthNorm } from "./circleRoi";

describe("createCirclePolygonFromRadius", () => {
  it("builds a closed circular polygon from center and radius tip", () => {
    const center = { x: 0.5, y: 0.5 };
    const tip = { x: 0.7, y: 0.5 };
    const points = createCirclePolygonFromRadius(center, tip, 1000, 1000, 32);

    expect(points.length).toBe(32);
    expect(points.every((point) => point.x >= 0 && point.x <= 1 && point.y >= 0 && point.y <= 1)).toBe(
      true,
    );

    const radius = radiusLengthNorm(center, tip, 1000, 1000);
    for (const point of points) {
      const distance = radiusLengthNorm(center, point, 1000, 1000);
      expect(Math.abs(distance - radius)).toBeLessThan(1.5);
    }
  });

  it("returns empty polygon when radius is near zero", () => {
    const center = { x: 0.4, y: 0.4 };
    expect(createCirclePolygonFromRadius(center, center, 800, 600)).toEqual([]);
  });

  it("keeps circle round in pixel space for non-square frames", () => {
    const center = { x: 0.5, y: 0.5 };
    const tip = { x: 0.75, y: 0.5 };
    const points = createCirclePolygonFromRadius(center, tip, 1600, 800, 48);
    const radius = radiusLengthNorm(center, tip, 1600, 800);

    for (const point of points) {
      const distance = radiusLengthNorm(center, point, 1600, 800);
      expect(Math.abs(distance - radius)).toBeLessThan(2);
    }
  });
});
