import { describe, expect, it } from "vitest";
import { learnedRegionBounds } from "./learnedRegion";

describe("learnedRegionBounds", () => {
  it("encloses disjoint anomalies and adds ten source pixels after scaling", () => {
    const bytes = new Uint8Array(100 * 80);
    bytes[20 * 100 + 30] = 1; bytes[50 * 100 + 70] = 255;
    expect(learnedRegionBounds(bytes, { width: 100, height: 80 }, 1000, 800))
      .toEqual({ x: 290, y: 190, width: 430, height: 330 });
  });
  it("clips padding at frame boundaries", () => {
    expect(learnedRegionBounds(new Uint8Array([1, 0, 0, 1]), { width: 2, height: 2 }, 100, 80))
      .toEqual({ x: 0, y: 0, width: 100, height: 80 });
  });
  it("rejects empty and truncated heatmaps", () => {
    expect(() => learnedRegionBounds(new Uint8Array(4), { width: 2, height: 2 }, 100, 80)).toThrow();
    expect(() => learnedRegionBounds(new Uint8Array(3), { width: 2, height: 2 }, 100, 80)).toThrow();
  });
});
