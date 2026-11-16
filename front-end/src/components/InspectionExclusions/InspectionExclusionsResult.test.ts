import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { InspectionExclusionsResult } from "./InspectionExclusionsResult";
import type { InspectionExclusions } from "../../shared/inspectionExclusions";

describe("inspection exclusion result", () => {
  it("explains when required label pixels are outside the camera image", () => {
    const exclusions: InspectionExclusions = {
      polygons: [], coordinate_space: "normalized", surface_skipped: false, remaining_area_work_px: 0,
      label_boundary: { status: "out_of_frame", max_inset_px: 0, candidates: 0, inliers: 0 },
    };
    const result=renderToStaticMarkup(createElement(InspectionExclusionsResult,{exclusions}));
    expect(result).toContain("Контролируемая часть этикетки вышла за кадр");
    expect(result).toContain('data-fallback="true"');
  });
  it("shows the actual refinement result and a non-rejecting fallback status", () => {
    const exclusions: InspectionExclusions = {
      polygons: [], coordinate_space: "normalized", surface_skipped: false, remaining_area_work_px: 100,
      label_boundary: { status: "refined", max_inset_px: 2.4, candidates: 100, inliers: 90 },
    };
    const success=renderToStaticMarkup(createElement(InspectionExclusionsResult,{exclusions}));
    expect(success).toContain("Граница этикетки уточнена");
    expect(success).toContain("2.4 px");
    exclusions.label_boundary!.status="insufficient_edges";
    const fallback=renderToStaticMarkup(createElement(InspectionExclusionsResult,{exclusions}));
    expect(fallback).toContain("используется подтверждённый контур");
    expect(fallback).not.toContain("БРАК");
    expect(fallback).not.toContain("ГОДЕН");
  });
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
