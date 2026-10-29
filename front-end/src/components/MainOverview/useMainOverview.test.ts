import type { Dispatch, SetStateAction } from "react";
import type { ModalInspectionSnapshot } from "./type";
import type { InspectResultPayload } from "../../shared/ws";
import { describe, expect, it } from "vitest";

import { setInspectionHistoryLimit } from "./MainController";
import { addModalInspectionItem, trimInspectionStatsItems } from "./useMainOverview";
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


describe("modal inspection artifacts", () => {
  it("adds a delayed heatmap to an already displayed frame without switching frames", () => {
    const initial = {
      camera_id: 0, frame_id: "42", session_state: "READY",
      current: { camera_id: 0, frame_id: "42", shm_name: "/cam", width: 10, height: 10,
        stride: 30, shm_offset: 0, pixel_format: "bgr_u8", channels: 3 },
      http_path: "/api/inspection-artifacts/frame42/frame.jpg",
      heatmap: null, fp_zones: [], active_reference_view_index: 0, detector: {},
      overall_pass: true, server_ts_ms: 100,
    } as InspectResultPayload;
    let snapshot: ModalInspectionSnapshot | null = {
      cameraId: 0, objectName: "Camera 0", inspectResult: initial,
      cameraImageUrl: initial.http_path, inspectionItems: [],
    };
    const setSnapshot: Dispatch<SetStateAction<ModalInspectionSnapshot | null>> = (action) => {
      snapshot = typeof action === "function" ? action(snapshot) : action;
    };
    const completed = { ...initial, server_ts_ms: 101, heatmap: {
      width: 10, height: 10, pixel_format: "gray_u8" as const, channels: 1,
      http_path: "/api/inspection-artifacts/frame42/heatmap.u8",
    }};
    addModalInspectionItem(setSnapshot, completed, undefined, new Map());
    expect(snapshot?.inspectResult?.heatmap).toEqual(completed.heatmap);
    expect(snapshot?.heatmapUrl).toContain("/frame42/heatmap.u8");
    addModalInspectionItem(setSnapshot, { ...completed, frame_id: "43" }, undefined, new Map());
    expect(snapshot?.inspectResult?.frame_id).toBe("42");
    addModalInspectionItem(setSnapshot, { ...initial, server_ts_ms: 102 }, undefined, new Map());
    expect(snapshot?.inspectResult?.heatmap).toEqual(completed.heatmap);
  });
});
