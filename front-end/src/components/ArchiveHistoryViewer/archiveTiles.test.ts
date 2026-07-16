import { describe, expect, it } from "vitest";
import type { InspectResultPayload } from "../../shared/ws";
import type { InspectionHistoryItem } from "../MainOverview/type";
import { createArchiveTiles } from "./archiveTiles";

function historyItem(
  cameraId: number,
  frameId: string,
  inspectionId: string,
  serverTsMs: number,
  result: InspectionHistoryItem["result"] = "pass",
): InspectionHistoryItem {
  const inspectResult: InspectResultPayload = {
    camera_id: cameraId,
    frame_id: frameId,
    inspection_id: inspectionId,
    session_state: "READY",
    current: {
      camera_id: cameraId,
      frame_id: frameId,
      shm_name: "",
      width: 0,
      height: 0,
      stride: 0,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
    },
    heatmap: null,
    active_reference_view_index: 0,
    detector: {},
    fp_zones: [],
    server_ts_ms: serverTsMs,
  };
  return { frameId, inspectionId, result, inspectResult };
}

describe("createArchiveTiles", () => {
  it("groups nearby frames from different cameras", () => {
    const tiles = createArchiveTiles([0, 1], {
      0: [historyItem(0, "100", "7", 1_000)],
      1: [historyItem(1, "200", "7", 1_050, "fail")],
    });

    expect(tiles).toHaveLength(1);
    expect(tiles[0].results.map((item) => item.inspectResult.camera_id)).toEqual([0, 1]);
    expect(tiles[0].result).toBe("fail");
  });

  it("does not merge a reused inspection id from another session", () => {
    const tiles = createArchiveTiles([0, 1], {
      0: [historyItem(0, "101", "7", 50_000), historyItem(0, "1", "7", 1_000)],
      1: [historyItem(1, "201", "7", 50_050), historyItem(1, "2", "7", 1_050)],
    });

    expect(tiles).toHaveLength(2);
    expect(tiles.map((tile) => tile.results.map((item) => item.frameId))).toEqual([
      ["101", "201"],
      ["1", "2"],
    ]);
  });

  it("never replaces another frame from the same camera", () => {
    const tiles = createArchiveTiles([0], {
      0: [historyItem(0, "2", "7", 2_000), historyItem(0, "1", "7", 1_000)],
    });

    expect(tiles).toHaveLength(2);
  });
});
