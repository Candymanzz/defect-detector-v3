import type { DetectorLineZone, InspectResultPayload } from "../ws";
import { formatAnomalyPercent } from "./anomalyScore";

export type DetectorFindingKind = "seam" | "edge" | "shift" | "size";

export type DetectorFinding = {
  kind: DetectorFindingKind;
  /** Short verdict for the card, e.g. "Кромка повреждена". */
  label: string;
  /** Numbers behind the verdict, for a tooltip. */
  detail?: string;
};

/**
 * What the detector complained about besides the area percentage: damaged seam / edge line, frame shift, wrong size.
 * Empty when the frame is fine or the detector reported nothing else (a plain "score over threshold" is explained
 * by the percentage itself).
 */
export function getDetectorFindings(inspectResult?: InspectResultPayload): DetectorFinding[] {
  const detector = inspectResult?.detector_result;
  if (!detector) {
    return [];
  }

  const findings: DetectorFinding[] = [];
  const reason = detector.reason ?? "";

  if (detector.lines?.seam?.reject || detector.seam?.reject || reason === "seam_line_deviation" || reason === "seam_deviation") {
    findings.push({
      kind: "seam",
      label: "Стык повреждён",
      detail: describeLineZone(detector.lines?.seam ?? detector.seam),
    });
  }
  if (detector.lines?.edge?.reject || reason === "edge_line_deviation" || reason === "edge_deviation") {
    findings.push({
      kind: "edge",
      label: "Кромка повреждена",
      detail: describeLineZone(detector.lines?.edge),
    });
  }
  if (reason === "shift_exceeded") {
    findings.push({ kind: "shift", label: "Кадр сдвинут относительно эталона" });
  }
  if (reason === "size_mismatch") {
    findings.push({ kind: "size", label: "Размер кадра не совпал с эталоном" });
  }

  return findings;
}

/** "0.00% / Кромка повреждена": the area percentage with the short findings after a slash. */
export function formatAnomalyWithFindings(inspectResult?: InspectResultPayload): string {
  const percent = formatAnomalyPercent(inspectResult?.anomaly_score);
  const findings = getDetectorFindings(inspectResult);
  return findings.length > 0 ? `${percent} / ${findings.map((finding) => finding.label).join(", ")}` : percent;
}

function describeLineZone(zone?: Partial<DetectorLineZone>) {
  if (!zone) {
    return undefined;
  }
  if (zone.gap_reject) {
    return "линия не найдена на длинном участке";
  }
  if (typeof zone.max_dev !== "number" || typeof zone.ratio !== "number") {
    return undefined;
  }
  const where = typeof zone.worst_t === "number" ? `, на ${(zone.worst_t * 100).toFixed(0)}% длины` : "";
  return `отклонение ${zone.max_dev.toFixed(2)} px, ${zone.ratio.toFixed(1)}× допуска${where}`;
}
