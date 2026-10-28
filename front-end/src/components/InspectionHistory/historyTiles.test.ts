import { describe, expect, it } from "vitest";
import type { InspectionHistoryItem } from "../MainOverview/type";
import { createInspectionHistoryTiles, selectInspectionHistoryTile } from "./historyTiles";

const CAMERA_IDS = [0, 1, 2, 3, 4];

function historyItem(
  cameraId: number,
  serverTsMs: number,
  inspectionId: string | undefined = "1126",
  frameId = "1083",
): InspectionHistoryItem {
  return {
    frameId,
    inspectionId: inspectionId ?? frameId,
    result: "pass",
    inspectResult: {
      camera_id: cameraId,
      frame_id: frameId,
      inspection_id: inspectionId,
      session_state: "READY",
      current: {
        camera_id: cameraId,
        frame_id: frameId,
        shm_name: "",
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
      fp_zones: [],
      server_ts_ms: serverTsMs,
    },
  };
}

describe("inspection history selection", () => {
  it.each([2, 3])("adds the remaining cameras when opened with %i results", (initialCount) => {
    const history: Record<number, InspectionHistoryItem[]> = Object.fromEntries(
      CAMERA_IDS.slice(0, initialCount).map((cameraId) => [cameraId, [historyItem(cameraId, 1000 + cameraId * 10)]]),
    );
    const opened = createInspectionHistoryTiles(CAMERA_IDS, history)[0];
    for (const cameraId of CAMERA_IDS.slice(initialCount)) {
      history[cameraId] = [historyItem(cameraId, 1000 + cameraId * 10)];
    }
    const items = createInspectionHistoryTiles(CAMERA_IDS, history);

    expect(items[0].groupKey).not.toBe(opened.groupKey);
    const selected = selectInspectionHistoryTile(items, opened);
    expect(selected?.results.map((item) => item.inspectResult.camera_id)).toEqual(CAMERA_IDS);
  });

  it("refreshes the JPEG and heatmap of an already selected frame", () => {
    const original = historyItem(0, 1000);
    const opened = createInspectionHistoryTiles(CAMERA_IDS, { 0: [original] })[0];
    const updated: InspectionHistoryItem = {
      ...original,
      inspectResult: {
        ...original.inspectResult,
        server_ts_ms: 1300,
        artifact_bundle_id: "frame-1083",
        http_path: "/api/frame-archive/cameras/0/frames/1083/frame.jpg",
        heatmap: { width: 10, height: 10, pixel_format: "gray_u8", channels: 1, http_path: "/heatmap.u8" },
      },
    };
    const items = createInspectionHistoryTiles(CAMERA_IDS, { 0: [updated] });

    expect(selectInspectionHistoryTile(items, opened)?.results[0].inspectResult).toBe(updated.inspectResult);
  });

  it("keeps the chosen inspection when a newer inspection arrives", () => {
    const original = historyItem(0, 1000);
    const opened = createInspectionHistoryTiles(CAMERA_IDS, { 0: [original] })[0];
    const items = createInspectionHistoryTiles(CAMERA_IDS, {
      0: [historyItem(0, 2000, "1127", "1084"), original],
      1: [historyItem(1, 1050)],
    });

    const selected = selectInspectionHistoryTile(items, opened);
    expect(selected?.inspectionId).toBe("1126");
    expect(selected?.results).toHaveLength(2);
  });

  it("does not switch to reused inspection and frame numbers after a restart", () => {
    const original = historyItem(0, 1000, "7", "1");
    const opened = createInspectionHistoryTiles(CAMERA_IDS, { 0: [original] })[0];
    const items = createInspectionHistoryTiles(CAMERA_IDS, {
      0: [historyItem(0, 50_000, "7", "1")],
      1: [historyItem(1, 50_050, "7", "1")],
    });

    expect(selectInspectionHistoryTile(items, opened)).toBe(opened);
  });

  it("distinguishes nearby groups that reuse an inspection id with different frames", () => {
    const original = historyItem(0, 1000, "7", "1");
    const opened = createInspectionHistoryTiles(CAMERA_IDS, { 0: [original] })[0];
    const items = createInspectionHistoryTiles(CAMERA_IDS, {
      0: [historyItem(0, 2000, "7", "2"), original],
    });

    expect(selectInspectionHistoryTile(items, opened)?.results[0].frameId).toBe("1");
  });

  it("retains the completed inspection when it leaves the rolling history", () => {
    const opened = createInspectionHistoryTiles(CAMERA_IDS, { 0: [historyItem(0, 1000)] })[0];
    const completed = createInspectionHistoryTiles(
      CAMERA_IDS,
      Object.fromEntries(CAMERA_IDS.map((cameraId) => [cameraId, [historyItem(cameraId, 1100 + cameraId * 10)]])),
    );
    const updatedSnapshot = selectInspectionHistoryTile(completed, opened);
    const pruned = createInspectionHistoryTiles(CAMERA_IDS, { 4: [historyItem(4, 1140)] });
    const prunedSnapshot = selectInspectionHistoryTile(pruned, updatedSnapshot);
    expect(prunedSnapshot?.results).toHaveLength(5);
    const selected = selectInspectionHistoryTile([], prunedSnapshot);

    expect(selected?.results).toHaveLength(5);
  });

  it("does not open a modal before an inspection is selected", () => {
    const items = createInspectionHistoryTiles(CAMERA_IDS, { 0: [historyItem(0, 1000)] });

    expect(selectInspectionHistoryTile(items, null)).toBeNull();
  });
});
