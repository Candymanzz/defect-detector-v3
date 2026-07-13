import { describe, expect, it } from "vitest";

import {
  isCaptureOnlyInspectResult,
  resolveInspectionResultState,
} from "./inspectResult";

describe("resolveInspectionResultState", () => {
  it("returns capture for CAPTURE action", () => {
    expect(resolveInspectionResultState({ action: "CAPTURE" })).toBe("capture");
  });

  it("returns pass for python PASS and ГОДЕН", () => {
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
