import { describe, expect, it } from "vitest";

import { createNormalizationLut, HEATMAP_COLOR_LUT } from "./HeatmapColor";

describe("createNormalizationLut", () => {
  it("returns zero lut for flat input", () => {
    const bytes = new Uint8Array([10, 10, 10, 10]);
    const lut = createNormalizationLut(bytes, bytes.length);

    expect(lut[10]).toBe(0);
    expect(lut[255]).toBe(0);
  });

  it("maps min..max range monotonically", () => {
    const bytes = new Uint8Array([0, 50, 100]);
    const lut = createNormalizationLut(bytes, bytes.length);

    expect(lut[0]).toBe(0);
    expect(lut[100]).toBe(255);
    expect(lut[50]).toBeGreaterThan(lut[25]);
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
