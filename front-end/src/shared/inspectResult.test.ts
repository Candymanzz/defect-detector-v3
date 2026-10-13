import { describe, expect, it } from "vitest";

import {
  isCaptureOnlyInspectResult as fullIsCaptureOnly,
  resolveInspectionResultState as fullResolveState,
} from "./inspectResult";
import type { InspectResultPayload } from "./ws";

function payload(fields: Partial<InspectResultPayload>): InspectResultPayload {
  return {
    camera_id: 0, frame_id: "1", session_state: "READY",
    current: {camera_id:0,frame_id:"1",shm_name:"/test",width:10,height:10,stride:30,
      shm_offset:0,pixel_format:"bgr_u8",channels:3},
    heatmap: null, fp_zones: [], active_reference_view_index: 0, detector: {}, server_ts_ms: 1,
    ...fields,
  };
}
const resolveInspectionResultState = (fields: Partial<InspectResultPayload>) => fullResolveState(payload(fields));
const isCaptureOnlyInspectResult = (fields?: Partial<InspectResultPayload>) => fullIsCaptureOnly(fields ? payload(fields) : undefined);

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
