import type { InspectResultPayload } from "./ws";

export function resolveInspectionResultState(inspectResult?: InspectResultPayload): "pass" | "fail" | undefined {
  const pythonStatus = inspectResult?.python_status?.toUpperCase();
  if (pythonStatus === "PASS") {
    console.log(pythonStatus);
    return "pass";
  }
  if (pythonStatus === "FAIL" || pythonStatus === "ERROR") {
    console.log(pythonStatus);
    return "fail";
  }

  return undefined;
}
