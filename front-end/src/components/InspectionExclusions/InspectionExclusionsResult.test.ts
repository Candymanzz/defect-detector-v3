import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { InspectionExclusionsResult } from "./InspectionExclusionsResult";
import type { InspectionExclusions } from "../../shared/inspectionExclusions";

describe("inspection exclusion result", () => {
  it("tolerates old results and distinguishes empty zones from a fully excluded surface", () => {
    expect(renderToStaticMarkup(createElement(InspectionExclusionsResult))).toBe("");
    const exclusions: InspectionExclusions = {
      polygons: [], coordinate_space: "normalized", surface_skipped: false, remaining_area_work_px: 100,
    };
    const empty = renderToStaticMarkup(createElement(InspectionExclusionsResult, { exclusions }));
    expect(empty).toContain("Исключающих зон: 0");
    expect(empty).not.toContain("Вся поверхность");
    const skipped = renderToStaticMarkup(createElement(InspectionExclusionsResult, {
      exclusions: { ...exclusions, polygons: [[[0, 0], [1, 0], [0, 1]]], surface_skipped: true, remaining_area_work_px: 0 },
    }));
    expect(skipped).toContain("Вся поверхность исключена");
    expect(skipped).toContain('points="0,0 1000,0 0,1000"');
    expect(skipped).not.toContain("<img");
  });
});
