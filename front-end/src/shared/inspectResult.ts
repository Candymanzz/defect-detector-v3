import type { InspectResultPayload } from "./ws";

export type InspectionVisualState = "pass" | "fail" | "capture";

/**
 * Цвет панелек: PASS только если годен и python, и geometry (через overall_pass/action),
 * либо оба stage-статуса PASS при отсутствии общего вердикта.
 */
export function resolveInspectionResultState(
  inspectResult?: InspectResultPayload,
): InspectionVisualState | undefined {
  if (isCaptureOnlyInspectResult(inspectResult)) {
    return "capture";
  }

  // Combined decision from orchestrator (python ∧ geometry).
  if (inspectResult?.overall_pass === false || inspectResult?.action === "REJECT") {
    return "fail";
  }
  if (inspectResult?.overall_pass === true || inspectResult?.action === "ACCEPT") {
    return "pass";
  }

  const python = normalizeStageStatus(inspectResult?.python_status);
  const geometry = normalizeStageStatus(inspectResult?.geometry_status);

  if (python === "fail" || geometry === "fail") {
    return "fail";
  }
  if (python === "pass" && geometry === "pass") {
    return "pass";
  }
  // Legacy payloads without geometry_status: do not paint green on python alone
  // when geometry fields are present but inconclusive — leave unset.
  if (python === "pass" && geometry === undefined && inspectResult?.geometry_status == null) {
    return "pass";
  }

  return undefined;
}

export function isCaptureOnlyInspectResult(inspectResult?: InspectResultPayload): boolean {
  if (!inspectResult) {
    return false;
  }
  if (inspectResult.action === "CAPTURE") {
    return true;
  }
  return inspectResult.python_status?.trim().toUpperCase() === "NO_REFERENCE";
}

function normalizeStageStatus(raw?: string): "pass" | "fail" | undefined {
  if (!raw) {
    return undefined;
  }
  const status = raw.trim().toUpperCase();
  if (status === "PASS" || status === "ГОДЕН") {
    return "pass";
  }
  if (status === "FAIL" || status === "ERROR" || status === "БРАК") {
    return "fail";
  }
  return undefined;
}
