import { describe, expect, it } from "vitest";
import { zoomExclusionView } from "./exclusionViewport";
import { exclusionPointFromImage } from "../../shared/inspectionExclusions";

describe("exclusion image viewport", () => {
  it("keeps the image point under a wheel zoom anchor fixed", () => {
    const view = zoomExclusionView({ zoom: 1, x: 0, y: 0 }, 2, 400, 200, 100, 25);
    expect(view).toEqual({ zoom: 2, x: -100, y: -25 });
    expect((100 - view.x) / view.zoom).toBe(100);
    expect((25 - view.y) / view.zoom).toBe(25);
  });
  it("limits zoom and pan and resets to fit without empty margins", () => {
    expect(zoomExclusionView({ zoom: 4, x: 500, y: -300 }, .1, 400, 200)).toEqual({ zoom: 1, x: 0, y: 0 });
    const view = zoomExclusionView({ zoom: 2, x: 200, y: 100 }, 99, 400, 200);
    expect(view.zoom).toBe(8); expect(view.x).toBeLessThanOrEqual(1400); expect(view.y).toBeLessThanOrEqual(700);
  });
  it("resolves the same normalized vertex after both zoom and pan", () => {
    const point = exclusionPointFromImage(100 + 399 * .3 / 2, 50 + 199 * .4 / 2,
      { left: 100, top: 50, width: 200, height: 100 }, 400, 200);
    const zoomed = exclusionPointFromImage(-120 + 399 * .3 * 2, 80 + 199 * .4 * 2,
      { left: -120, top: 80, width: 800, height: 400 }, 400, 200);
    expect(point?.[0]).toBeCloseTo(.3); expect(point?.[1]).toBeCloseTo(.4);
    expect(zoomed?.[0]).toBeCloseTo(point![0]); expect(zoomed?.[1]).toBeCloseTo(point![1]);
  });
});
