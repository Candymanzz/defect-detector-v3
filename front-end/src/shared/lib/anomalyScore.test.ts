import { describe, expect, it } from "vitest";
import { formatAnomalyPercent } from "./anomalyScore";

describe("formatAnomalyPercent", () => {
  it("formats a normalized anomaly score as a percentage", () => {
    expect(formatAnomalyPercent(0.02)).toBe("2.00%");
    expect(formatAnomalyPercent(0.815)).toBe("81.50%");
    expect(formatAnomalyPercent(1)).toBe("100.00%");
  });

  it("does not display a missing or non-finite score", () => {
    expect(formatAnomalyPercent()).toBe("—");
    expect(formatAnomalyPercent(null)).toBe("—");
    expect(formatAnomalyPercent(Number.NaN)).toBe("—");
  });
});
