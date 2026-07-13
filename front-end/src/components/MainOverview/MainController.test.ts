import { describe, expect, it } from "vitest";

import {
  compareInspectResults,
  upsertInspectionHistoryItem,
} from "./MainController";
import type { InspectResultPayload } from "../../shared/ws";

function inspectResult(frameId: string, serverTs: number): InspectResultPayload {
  return {
    camera_id: 0,
    frame_id: frameId,
    session_state: "ready",
    current: {
      camera_id: 0,
      frame_id: frameId,
      shm_name: "/cam",
      width: 10,
      height: 10,
      stride: 30,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
    },
    heatmap: null,
    active_reference_view_index: 0,
    detector: {},
    server_ts_ms: serverTs,
  };
}

describe("MainController helpers", () => {
  it("compareInspectResults sorts by frame id then timestamp", () => {
    const left = inspectResult("10", 100);
    const right = inspectResult("9", 200);

    expect(compareInspectResults(left, right)).toBeGreaterThan(0);
    expect(compareInspectResults(left, inspectResult("10", 50))).toBeGreaterThan(0);
  });

  it("upsertInspectionHistoryItem replaces same frame id", () => {
    const frame = inspectResult("1", 1);
    const updated = upsertInspectionHistoryItem(
      [
        {
          frameId: "1",
          inspectionId: "1",
          result: "pass",
          inspectResult: frame,
        },
      ],
      {
        frameId: "1",
        inspectionId: "1",
        result: "fail",
        inspectResult: { ...frame, server_ts_ms: 2 },
      },
    );

    expect(updated).toHaveLength(1);
    expect(updated[0].result).toBe("fail");
  });
});
