import { describe, expect, it } from "vitest";

import { setInspectionHistoryLimit } from "./MainController";
import { trimInspectionStatsItems } from "./useMainOverview";
import type { InspectionHistoryItem } from "./type";

describe("inspection statistics retention", () => {
  it("keeps a bounded number of the newest per-camera results", () => {
    const configuredLimit = 7;
    setInspectionHistoryLimit(configuredLimit);
    const items = Array.from(
      { length: configuredLimit + 5 },
      (_, index) => ({ frameId: String(index), inspectionId: String(index), result: "pass" }) as InspectionHistoryItem,
    );

    const retained = trimInspectionStatsItems(items);

    expect(retained).toHaveLength(configuredLimit);
    expect(retained[0].frameId).toBe("0");
    expect(retained[retained.length - 1]?.frameId).toBe(String(configuredLimit - 1));
  });
});
