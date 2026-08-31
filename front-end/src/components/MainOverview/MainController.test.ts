import { describe, expect, it } from "vitest";

import {
  compareInspectResults,
  isPreviewFrameNewerOrEqual,
  selectModalInspection,
  upsertInspectionHistoryItem,
} from "./MainController";
import type { InspectResultPayload } from "../../shared/ws";

function inspectResult(frameId: string, serverTs: number): InspectResultPayload {
  return {
    camera_id: 0,
    frame_id: frameId,
    session_state: "READY",
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
    fp_zones: [],
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

  it("rejects an older preview frame when server timestamps are equal", () => {
    expect(isPreviewFrameNewerOrEqual({ frame_id: "9", server_ts_ms: 100 }, "10", 100)).toBe(false);
    expect(isPreviewFrameNewerOrEqual({ frame_id: "11", server_ts_ms: 100 }, "10", 100)).toBe(true);
  });

  it("allows frame ids to restart when the server timestamp advances", () => {
    expect(isPreviewFrameNewerOrEqual({ frame_id: "1", server_ts_ms: 101 }, "1000", 100)).toBe(true);
  });

  it("updates the modal image URL when selecting an older inspection", () => {
    const frame26 = { ...inspectResult("26", 260), http_path: "/api/frame-archive/cameras/0/frames/26.jpg" };
    const frame25 = { ...inspectResult("25", 250), http_path: "/api/frame-archive/cameras/0/frames/25.jpg" };
    const snapshot = {
      cameraId: 0,
      objectName: "Camera 0",
      inspectResult: frame26,
      cameraImageUrl: `http://127.0.0.1:8099${frame26.http_path}?frame_ts=26`,
      inspectionItems: [
        { frameId: "26", inspectionId: "26", result: "pass" as const, inspectResult: frame26 },
        { frameId: "25", inspectionId: "25", result: "pass" as const, inspectResult: frame25 },
      ],
    };

    const selected = selectModalInspection(snapshot, "25");

    expect(selected?.inspectResult?.frame_id).toBe("25");
    expect(selected?.cameraImageUrl).toContain("25.jpg");
    expect(selected?.cameraImageUrl).toContain("frame_ts=25");
  });
});
