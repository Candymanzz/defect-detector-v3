import { describe, expect, it } from "vitest";
import type { InspectResultPayload } from "../ws";
import { formatAnomalyWithFindings, getDetectorFindings } from "./detectorFindings";

function result(overrides: Partial<InspectResultPayload>): InspectResultPayload {
  return { anomaly_score: 0, ...overrides } as InspectResultPayload;
}

describe("getDetectorFindings", () => {
  it("is empty without detector details or for a clean frame", () => {
    expect(getDetectorFindings(undefined)).toEqual([]);
    expect(getDetectorFindings(result({}))).toEqual([]);
    expect(getDetectorFindings(result({ detector_result: { reason: "", lines: {} } }))).toEqual([]);
  });

  it("does not explain a plain over-threshold score (the percentage does)", () => {
    expect(getDetectorFindings(result({ detector_result: { reason: "score_over_threshold" } }))).toEqual([]);
  });

  it("reports a damaged edge and seam from the zone verdicts", () => {
    const findings = getDetectorFindings(
      result({
        detector_result: {
          reason: "seam_line_deviation",
          lines: {
            seam: { found: true, reject: true, ratio: 3.2, max_dev: 4.8, worst_t: 0.617 },
            edge: { found: true, reject: true, ratio: 2, max_dev: 3, worst_t: 0.1 },
          },
        },
      }),
    );
    expect(findings.map((finding) => finding.label)).toEqual(["Стык повреждён", "Кромка повреждена"]);
    expect(findings[0].detail).toBe("отклонение 4.80 px, 3.2× допуска, на 62% длины");
  });

  it("reports only the zone that rejected", () => {
    const findings = getDetectorFindings(
      result({
        detector_result: {
          lines: {
            seam: { found: true, reject: false, ratio: 0.3, max_dev: 0.3, worst_t: 0.5 },
            edge: { found: true, reject: true, gap_reject: true, ratio: 0, max_dev: 0, worst_t: 0 },
          },
        },
      }),
    );
    expect(findings).toEqual([
      { kind: "edge", label: "Кромка повреждена", detail: "линия не найдена на длинном участке" },
    ]);
  });
});

describe("formatAnomalyWithFindings", () => {
  it("appends short findings after a slash", () => {
    const edgeReject = result({
      detector_result: { lines: { edge: { found: true, reject: true, ratio: 2, max_dev: 3, worst_t: 0 } } },
    });
    expect(formatAnomalyWithFindings(edgeReject)).toBe("0.00% / Кромка повреждена");
    expect(formatAnomalyWithFindings(result({ anomaly_score: 0.02 }))).toBe("2.00%");
  });
});
