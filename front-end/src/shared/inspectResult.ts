import type { InspectResultPayload } from "./ws";

export function resolveInspectionResultState(
  inspectResult?: InspectResultPayload,
): "pass" | "fail" | undefined {
  if (isCaptureOnlyInspectResult(inspectResult)) {
    return undefined;
  }
  const pythonStatus = inspectResult?.python_status?.trim().toUpperCase();
  if (pythonStatus === "PASS" || pythonStatus === "ГОДЕН") {
    return "pass";
  }
  if (pythonStatus === "FAIL" || pythonStatus === "ERROR" || pythonStatus === "БРАК") {
    return "fail";
  }

  if (inspectResult?.overall_pass === true || inspectResult?.action === "ACCEPT") {
    return "pass";
  }
  if (inspectResult?.overall_pass === false || inspectResult?.action === "REJECT") {
    return "fail";
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
