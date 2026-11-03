import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DetectorSettingsPanel, editableSettings } from "./DetectorSettingsPanel";
import type { DetectorSettings } from "../../shared/api";

describe("DetectorSettingsPanel metric limits", () => {
  it("shows rotation and vertical limits in mm, with separate pixel comparison relaxation", () => {
    const html = renderToStaticMarkup(createElement(DetectorSettingsPanel, { selectedCameraId: null }));
    expect(html).toContain('aria-label="Ослабление сравнения, px"');
    expect(html).toContain("Миллиметры пока приблизительные");
    expect(html).not.toContain("Максимальное смещение X");
    expect(html).not.toContain("Максимальное смещение Y");
    for (const label of ["Допуск проворота", "Допуск вертикального смещения"]) {
      const input = html.match(new RegExp(`<input[^>]*aria-label="${label}, мм"[^>]*>`))?.[0];
      expect(input).toBeDefined();
      for (const attribute of ['type="number"', 'min="0"', 'max="10"', 'step="0.05"']) {
        expect(input).toContain(attribute);
      }
    }
  });

  it("sends mm and shared relaxation without copying camera scale or effective pixel limits", () => {
    const settings: DetectorSettings = {
      sensitivity: .5, threshold: .6, shift_tolerance_x: 2, shift_tolerance_y: 2,
      max_alignment_shift_x: 12, max_alignment_shift_y: 12,
      horizontal_shift_tolerance_mm: 0, vertical_shift_tolerance_mm: 4,
      estimated_mm_per_pixel: [.27, .34], metric_scale_source: "estimated",
      min_blob_area: 20, inspect_scale: .5,
      learning_robust_enabled: false, learning_shadow_enabled: false, learning_search_radius: 24,
      seam: {enabled: false, p0: [0,0], p1: [0,0], band: 30, tol_mid: 1, tol_edge: 5, edge_frac: .12},
    };
    const common = editableSettings(settings, false);
    expect(common.learning_robust_enabled).toBe(false);
    expect(common.learning_shadow_enabled).toBe(false);
    expect(common.learning_search_radius).toBe(24);
    expect(common.horizontal_shift_tolerance_mm).toBe(0);
    expect(common.vertical_shift_tolerance_mm).toBe(4);
    expect(common.shift_tolerance_x).toBe(2);
    expect(common.shift_tolerance_y).toBe(2);
    expect(common).not.toHaveProperty("estimated_mm_per_pixel");
    expect(common).not.toHaveProperty("max_alignment_shift_x");
    expect(common).not.toHaveProperty("max_alignment_shift_y");
    expect(common).not.toHaveProperty("seam");
    expect(editableSettings(settings, true).seam).toEqual(settings.seam);
  });
  it("exposes a separate bounded learning radius and both fallback switches", () => {
    const html = renderToStaticMarkup(createElement(DetectorSettingsPanel, { selectedCameraId: 1 }));
    const radius = html.match(/<input[^>]*aria-label="Поиск образца нормы, px"[^>]*>/)?.[0];
    for (const attribute of ['min="0"', 'max="64"', 'value="24"']) expect(radius).toContain(attribute);
    expect(html).toContain("Устойчивое сопоставление образцов");
    expect(html).toContain("Учитывать смещение плавной тени");
  });
});
