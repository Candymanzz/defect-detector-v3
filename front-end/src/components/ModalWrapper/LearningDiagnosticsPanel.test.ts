import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { LearningDiagnosticsPanel } from "./LearningDiagnosticsPanel";

describe("frame-bound learning diagnostics", () => {
  it("shows shadow displacement, outcome and rejected illumination explanation", () => {
    const html = renderToStaticMarkup(createElement(LearningDiagnosticsPanel, { learning: {
      learning_engine: "robust-v2", learning_robust_enabled: true, learning_ms: 2,
      raw_anomaly_score: 0.4, learned_normal_matches_count: 1,
      learning_diagnostics: [
        { id: "normal-1", status: "matched", method: "shadow", shift: [20, 8], removed_pixels: 200, shadow_correlation: 0.95 },
        { id: "normal-2", status: "no_effect", method: "none", removed_pixels: 0, shadow_skip_reason: "lighting_change_too_large" },
      ],
    } }));
    for (const text of ["40.00%", "normal-1", "20.00, 8.00", "200", "0.95", "слишком сильно изменилась яркость"]) expect(html).toContain(text);
  });
  it("keeps old payloads quiet and distinguishes disabled native mode", () => {
    expect(renderToStaticMarkup(createElement(LearningDiagnosticsPanel, {}))).toBe("");
    expect(renderToStaticMarkup(createElement(LearningDiagnosticsPanel, { learning: {
      learning_engine: "native-52aa31ea", learning_robust_enabled: false,
    } }))).toContain("Устойчивый режим выключен");
  });
});
