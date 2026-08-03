import { describe, expect, it } from "vitest";

import {
  isCaptureOnlyInspectResult,
  resolveInspectionResultState,
} from "./inspectResult";

describe("resolveInspectionResultState", () => {
  it("returns capture for CAPTURE action", () => {
    expect(resolveInspectionResultState({ action: "CAPTURE" })).toBe("capture");
  });

  it("prefers overall_pass over python_status alone", () => {
    expect(
      resolveInspectionResultState({
        python_status: "PASS",
        geometry_status: "FAIL",
        overall_pass: false,
        action: "REJECT",
      }),
    ).toBe("fail");
    expect(
      resolveInspectionResultState({
        python_status: "FAIL",
        geometry_status: "PASS",
        overall_pass: false,
      }),
    ).toBe("fail");
    expect(
      resolveInspectionResultState({
        python_status: "PASS",
        geometry_status: "PASS",
        overall_pass: true,
        action: "ACCEPT",
      }),
    ).toBe("pass");
  });

  it("fails when geometry fails even if python passes (no overall)", () => {
    expect(
      resolveInspectionResultState({
        python_status: "PASS",
        geometry_status: "FAIL",
      }),
    ).toBe("fail");
  });

  it("passes only when both stage statuses pass without overall", () => {
    expect(
      resolveInspectionResultState({
        python_status: "PASS",
        geometry_status: "PASS",
      }),
    ).toBe("pass");
  });

  it("returns pass for legacy python PASS without geometry fields", () => {
    expect(resolveInspectionResultState({ python_status: "PASS" })).toBe("pass");
    expect(resolveInspectionResultState({ python_status: "годен" })).toBe("pass");
  });

  it("returns fail for python FAIL, ERROR and БРАК", () => {
    expect(resolveInspectionResultState({ python_status: "FAIL" })).toBe("fail");
    expect(resolveInspectionResultState({ python_status: "ERROR" })).toBe("fail");
    expect(resolveInspectionResultState({ python_status: "брак" })).toBe("fail");
  });

  it("falls back to overall_pass and action", () => {
    expect(resolveInspectionResultState({ overall_pass: true })).toBe("pass");
    expect(resolveInspectionResultState({ action: "REJECT" })).toBe("fail");
    expect(resolveInspectionResultState({})).toBeUndefined();
  });
});

describe("isCaptureOnlyInspectResult", () => {
  it("detects CAPTURE and NO_REFERENCE", () => {
    expect(isCaptureOnlyInspectResult({ action: "CAPTURE" })).toBe(true);
    expect(isCaptureOnlyInspectResult({ python_status: "NO_REFERENCE" })).toBe(true);
    expect(isCaptureOnlyInspectResult({ python_status: "PASS" })).toBe(false);
    expect(isCaptureOnlyInspectResult(undefined)).toBe(false);
  });
});
