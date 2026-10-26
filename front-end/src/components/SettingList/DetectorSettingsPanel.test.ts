import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DetectorSettingsPanel } from "./DetectorSettingsPanel";

describe("DetectorSettingsPanel shift tolerances", () => {
  it("shows independent horizontal and vertical controls instead of the old shared control", () => {
    const html = renderToStaticMarkup(createElement(DetectorSettingsPanel, { selectedCameraId: null }));
    expect(html).toContain('aria-label="Допуск на сдвиг X, px"');
    expect(html).toContain('aria-label="Допуск на сдвиг Y, px"');
    expect(html).not.toContain('aria-label="Допуск на сдвиг, px"');
    for (const axis of ["X", "Y"]) {
      const input = html.match(new RegExp(`<input[^>]*aria-label="Допуск на сдвиг ${axis}, px"[^>]*>`))?.[0];
      expect(input).toBeDefined();
      for (const attribute of ['type="number"', 'min="0"', 'max="15"', 'step="1"', 'value="3"']) {
        expect(input).toContain(attribute);
      }
    }
  });
});
