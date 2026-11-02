import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DetectorSettingsPanel } from "./DetectorSettingsPanel";

describe("DetectorSettingsPanel comparison and learning controls", () => {
  it("keeps global alignment independent from the shared comparison relaxation", () => {
    const html = renderToStaticMarkup(createElement(DetectorSettingsPanel, { selectedCameraId: null }));
    expect(html).toContain('aria-label="Ослабление сравнения, px"');
    for (const axis of ["X", "Y"]) {
      const input = html.match(new RegExp(`<input[^>]*aria-label="Максимальное смещение ${axis}, px"[^>]*>`))?.[0];
      expect(input).toBeDefined();
      for (const attribute of ['type="number"', 'min="0"', 'max="160"', 'step="1"', 'value="12.00"']) {
        expect(input).toContain(attribute);
      }
    }
  });
  it("exposes a separate bounded learning radius and both fallback switches", () => {
    const html = renderToStaticMarkup(createElement(DetectorSettingsPanel, { selectedCameraId: 1 }));
    const radius = html.match(/<input[^>]*aria-label="Поиск образца нормы, px"[^>]*>/)?.[0];
    for (const attribute of ['min="0"', 'max="64"', 'value="24"']) expect(radius).toContain(attribute);
    expect(html).toContain("Устойчивое сопоставление образцов");
    expect(html).toContain("Учитывать смещение плавной тени");
  });
});
