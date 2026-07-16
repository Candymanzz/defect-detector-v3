import { inspectionHistoryLimit } from "../MainOverview/MainController";
import type { InspectionHistoryItem } from "../MainOverview/type";

export type ArchiveTile = {
  groupKey: string;
  inspectionId: string;
  result: "pass" | "fail" | "capture";
  serverTsMs: number;
  results: InspectionHistoryItem[];
};

const ARCHIVE_GROUP_WINDOW_MS = 10_000;

export function createArchiveTiles(
  cameraIds: number[],
  historyByCameraId: Record<number, InspectionHistoryItem[]>,
) {
  const groupsByInspectionId = new Map<string, ArchiveTile[]>();

  for (const cameraId of cameraIds) {
    for (const item of historyByCameraId[cameraId] ?? []) {
      const inspectionId = item.inspectionId || item.frameId;
      const candidates = groupsByInspectionId.get(inspectionId) ?? [];
      const existing = candidates
        .filter(
          (group) =>
            !group.results.some((result) => result.inspectResult.camera_id === item.inspectResult.camera_id) &&
            Math.abs(group.serverTsMs - item.inspectResult.server_ts_ms) <= ARCHIVE_GROUP_WINDOW_MS,
        )
        .sort(
          (left, right) =>
            Math.abs(left.serverTsMs - item.inspectResult.server_ts_ms) -
            Math.abs(right.serverTsMs - item.inspectResult.server_ts_ms),
        )[0];

      if (!existing) {
        candidates.push({
          groupKey: `archive:${inspectionId}:${item.inspectResult.camera_id}:${item.frameId}`,
          inspectionId,
          result: item.result,
          serverTsMs: item.inspectResult.server_ts_ms,
          results: [item],
        });
        groupsByInspectionId.set(inspectionId, candidates);
        continue;
      }

      existing.results.push(item);
      existing.result = mergeResult(existing.result, item.result);
      existing.serverTsMs = Math.max(existing.serverTsMs, item.inspectResult.server_ts_ms);
    }
  }

  return [...groupsByInspectionId.values()]
    .flat()
    .map((group) => ({
      ...group,
      results: [...group.results].sort(
        (left, right) => left.inspectResult.camera_id - right.inspectResult.camera_id,
      ),
    }))
    .sort((left, right) => {
      const byTime = right.serverTsMs - left.serverTsMs;
      return byTime !== 0 ? byTime : right.inspectionId.localeCompare(left.inspectionId);
    })
    .slice(0, Math.max(inspectionHistoryLimit, 1) * Math.max(cameraIds.length, 1));
}

function mergeResult(left: ArchiveTile["result"], right: ArchiveTile["result"]) {
  if (left === "fail" || right === "fail") {
    return "fail";
  }
  if (left === "capture" || right === "capture") {
    return "capture";
  }
  return "pass";
}
