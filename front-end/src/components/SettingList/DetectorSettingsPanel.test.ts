import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DetectorSettingsPanel, editableSettings } from "./DetectorSettingsPanel";
import type { DetectorSettings } from "../../shared/api";

describe("DetectorSettingsPanel shift tolerances", () => {
  it("shows horizontal and vertical physical tolerances without requiring calibration", () => {
    const html = renderToStaticMarkup(createElement(DetectorSettingsPanel, { selectedCameraId: null }));
    expect(html).toContain("Миллиметры пока приблизительные");
    expect(html).toContain("0,2% отмеченной площади ROI");
    for (const axis of ["горизонтали", "вертикали"]) {
      const input = html.match(new RegExp(`<input[^>]*aria-label="Допуск на смещение по ${axis}, мм"[^>]*>`))?.[0];
      expect(input).toBeDefined();
      for (const attribute of ['type="number"', 'min="0"', 'max="10"', 'step="0.01"']) {
        expect(input).toContain(attribute);
      }
    }
  });

  it("saves millimeters without sending legacy pixel values or another camera's scale", () => {
    const settings: DetectorSettings = {
      sensitivity:.5, threshold:.6, horizontal_shift_tolerance_mm:0, vertical_shift_tolerance_mm:4,
      shift_tolerance_x:30, shift_tolerance_y:30, min_blob_area:20, inspect_scale:.5,
      estimated_mm_per_pixel:[.27,.34], metric_scale_source:"estimated",
      seam:{enabled:true,p0:[.2,.2],p1:[.2,.8],band:30,tol_mid:1,tol_edge:5,edge_frac:.12,min_contrast:8},
    };
    const common=editableSettings(settings,false);
    expect(common.horizontal_shift_tolerance_mm).toBe(0);
    expect(common.vertical_shift_tolerance_mm).toBe(4);
    expect(common).not.toHaveProperty("seam");
    expect(common).not.toHaveProperty("estimated_mm_per_pixel");
    expect(common).not.toHaveProperty("shift_tolerance_x");
    expect(common).not.toHaveProperty("shift_tolerance_y");
    expect(editableSettings(settings,true).seam).toEqual(settings.seam);
  });
});
