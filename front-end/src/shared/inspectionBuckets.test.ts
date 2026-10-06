import { describe, expect, it } from "vitest";
import { createInspectionBuckets } from "./inspectionBuckets";

describe("createInspectionBuckets", () => {
  it("splits ten cameras into four buckets across two phases", () => {
    expect(createInspectionBuckets([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])).toEqual([
      { key: "0:0", phaseId: 0, groupId: 0, cameraIds: [0, 1, 2, 3, 4], label: "Ведро 1" },
      { key: "0:1", phaseId: 0, groupId: 1, cameraIds: [5, 6, 7, 8, 9], label: "Ведро 2" },
      { key: "1:2", phaseId: 1, groupId: 2, cameraIds: [0, 1, 2, 3, 4], label: "Ведро 3" },
      { key: "1:3", phaseId: 1, groupId: 3, cameraIds: [5, 6, 7, 8, 9], label: "Ведро 4" },
    ]);
  });
});
