import { describe, expect, it } from "vitest";

import { createNormalizationLut, HEATMAP_COLOR_LUT } from "./HeatmapColor";

describe("createNormalizationLut", () => {
  it("keeps zero transparent and makes flat non-zero input visible", () => {
    const lut = createNormalizationLut();

    expect(lut[0]).toBe(0);
    expect(lut[10]).toBeGreaterThan(0);
    expect(lut[255]).toBe(255);
  });

  it("uses the same monotonic scale for every frame", () => {
    const lut = createNormalizationLut();

    expect(lut[0]).toBe(0);
    expect(lut[50]).toBeGreaterThan(lut[25]);
    expect(lut[100]).toBeGreaterThan(lut[50]);
  });
});

describe("HEATMAP_COLOR_LUT", () => {
  it("keeps zero-energy pixels fully transparent", () => {
    expect(HEATMAP_COLOR_LUT[3]).toBe(0);
  });

  it("makes peak energy opaque", () => {
    expect(HEATMAP_COLOR_LUT[255 * 4 + 3]).toBeGreaterThan(200);
  });
});
