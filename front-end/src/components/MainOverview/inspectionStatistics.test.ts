import { afterEach, describe, expect, it } from "vitest";
import { InspectionStatistics } from "./inspectionStatistics";
import { setInspectionHistoryLimit, upsertInspectionHistoryItem } from "./MainController";
import type { InspectBucketResultPayload, InspectResultPayload } from "../../shared/ws";
import type { InspectionHistoryItem } from "./type";

const cameras = Array.from({ length: 10 }, (_, id) => id);
function result(id: number, cameraId = 0, pass = true): InspectResultPayload {
  return { camera_id: cameraId, frame_id: String(id), inspection_id: String(id), session_state: "READY",
    server_ts_ms: id * 100, overall_pass: pass, action: pass ? "ACCEPT" : "REJECT",
    current: { camera_id: cameraId, frame_id: String(id), shm_name: "", width: 10, height: 10,
      stride: 30, shm_offset: 0, pixel_format: "bgr_u8", channels: 3 },
    heatmap: null, detector: {}, fp_zones: [], active_reference_view_index: 0 };
}
function bucket(id: number, groupId = 0, pass = true): InspectBucketResultPayload {
  return { group_id: groupId, trigger_sequence: id, overall_pass: pass, session_state: "READY", server_ts_ms: id * 100,
    bucket_camera_ids: cameras.slice(groupId * 5, groupId * 5 + 5), reject_camera_ids: pass ? [] : [groupId * 5], frames: [] };
}
afterEach(() => setInspectionHistoryLimit(30));

describe("cumulative inspection statistics", () => {
  it.each([0, 1, 7])("keeps counting while saved history is limited to %s frames", limit => {
    setInspectionHistoryLimit(limit);
    const statistics = new InspectionStatistics(cameras);
    let history: InspectionHistoryItem[] = [];
    for (let id = 1; id <= 100; id++) {
      const payload = result(id, 0, id % 5 !== 0);
      statistics.recordInspection(payload);
      history = upsertInspectionHistoryItem(history, { frameId: payload.frame_id, inspectionId: payload.inspection_id!,
        result: id % 5 ? "pass" : "fail", inspectResult: payload });
    }
    expect(history).toHaveLength(limit);
    expect(statistics.getSnapshot()).toMatchObject({ total: 100, passed: 80, failed: 20 });
    expect(statistics.getSnapshot().groups[0]).toMatchObject({ total: 100, passed: 80, failed: 20 });
    setInspectionHistoryLimit(0);
    statistics.recordInspection(result(101));
    expect(statistics.getSnapshot().total).toBe(101);
  });

  it("deduplicates all five cameras, delayed heatmaps and final bucket notifications", () => {
    const statistics = new InspectionStatistics(cameras);
    for (let camera = 0; camera < 5; camera++) statistics.recordInspection(result(42, camera));
    const snapshot = statistics.getSnapshot();
    statistics.recordInspection({ ...result(42), server_ts_ms: 5000, artifact_bundle_id: "late-heatmap" });
    expect(statistics.getSnapshot()).toBe(snapshot);
    expect(snapshot.total).toBe(1);
    statistics.recordBucket(bucket(42, 0, false));
    expect(statistics.getSnapshot()).toMatchObject({ total: 1, passed: 0, failed: 1 });
    statistics.recordInspection(result(42));
    statistics.recordBucket(bucket(42, 0, false));
    expect(statistics.getSnapshot()).toMatchObject({ total: 1, passed: 0, failed: 1 });
  });

  it("counts the two groups separately and one overall cycle across both groups", () => {
    const statistics = new InspectionStatistics(cameras);
    statistics.recordBucket(bucket(42, 0));
    statistics.recordBucket(bucket(42, 1, false));
    const snapshot = statistics.getSnapshot();
    expect(snapshot).toMatchObject({ total: 1, passed: 0, failed: 1 });
    expect(snapshot.groups[0]).toMatchObject({ total: 1, passed: 1, failed: 0 });
    expect(snapshot.groups[1]).toMatchObject({ total: 1, passed: 0, failed: 1 });
  });

  it("does not rebuild reset statistics from delayed events, TEST reruns or capture-only frames", () => {
    const statistics = new InspectionStatistics(cameras);
    statistics.recordInspection(result(42));
    statistics.reset();
    statistics.recordInspection({ ...result(42), server_ts_ms: 99999, artifact_bundle_id: "late" });
    statistics.recordBucket(bucket(42));
    statistics.recordInspection({ ...result(43), test_analyze: true });
    statistics.recordInspection({ ...result(44), action: "CAPTURE", python_status: "NO_REFERENCE" });
    statistics.recordBucket({ ...bucket(45), session_state: "NO_REFERENCE" });
    expect(statistics.getSnapshot().total).toBe(0);
    statistics.recordInspection(result(46));
    expect(statistics.getSnapshot().total).toBe(1);
  });

  it("resets once when capture counters restart on multiple cameras", () => {
    const statistics = new InspectionStatistics(cameras);
    const preview = (camera_id: number, frame_id: string, server_ts_ms: number) => ({ camera_id, frame_id, server_ts_ms });
    statistics.observeFrame(preview(0, "120", 100));
    statistics.observeFrame(preview(1, "119", 100));
    statistics.recordInspection(result(120));
    statistics.observeFrame(preview(0, "1", 200));
    expect(statistics.getSnapshot().total).toBe(0);
    statistics.recordInspection(result(1));
    statistics.observeFrame(preview(1, "1", 201));
    statistics.observeFrame(preview(0, "0", 99));
    expect(statistics.getSnapshot().total).toBe(1);
    statistics.recordInspection(result(2));
    expect(statistics.getSnapshot().total).toBe(2);
  });

  it("accumulates long runs without retaining result payloads or losing old duplicate protection", () => {
    const statistics = new InspectionStatistics(cameras);
    for (let id = 1; id <= 20000; id++) statistics.recordBucket(bucket(id, 0, id % 4 !== 0));
    statistics.recordBucket(bucket(1, 0));
    expect(statistics.getSnapshot()).toMatchObject({ total: 20000, passed: 15000, failed: 5000 });
    expect(JSON.stringify(statistics.getSnapshot()).length).toBeLessThan(1000);
    expect(JSON.stringify(statistics.getSnapshot())).not.toContain("heatmap");
  });
});
