import { inspectionHistoryLimit } from "../MainOverview/MainController";
import type { InspectionHistoryItem } from "../MainOverview/type";

const INSPECTION_GROUP_WINDOW_MS = 15_000;

export type InspectionHistoryTile = {
  groupKey: string;
  inspectionId: string;
  groupSource: "inspection" | "frame" | "bucket";
  result: "pass" | "fail" | "capture";
  serverTsMs: number;
  results: InspectionHistoryItem[];
};

export function selectInspectionHistoryTile(
  items: InspectionHistoryTile[],
  snapshot: InspectionHistoryTile | null,
): InspectionHistoryTile | null {
  if (!snapshot) {
    return null;
  }
  // A group's key follows its newest result and changes as cameras/artifacts arrive.
  // Match a frame already in the opened inspection, keeping reused ids in separate sessions.
  const current = items.find(
    (item) =>
      item.groupSource === snapshot.groupSource &&
      item.inspectionId === snapshot.inspectionId &&
      item.results.some((result) =>
        snapshot.results.some(
          (previous) =>
            previous.inspectResult.camera_id === result.inspectResult.camera_id &&
            previous.frameId === result.frameId &&
            Math.abs(previous.inspectResult.server_ts_ms - result.inspectResult.server_ts_ms) <=
              INSPECTION_GROUP_WINDOW_MS,
        ),
      ),
  );
  if (!current) {
    return snapshot;
  }
  const retainedResults = snapshot.results.filter(
    (previous) =>
      !current.results.some((result) => result.inspectResult.camera_id === previous.inspectResult.camera_id),
  );
  if (retainedResults.length === 0) {
    return current;
  }
  const results = [...current.results, ...retainedResults].sort(
    (left, right) => left.inspectResult.camera_id - right.inspectResult.camera_id,
  );
  return {
    ...current,
    results,
    result: resolveGroupResult(results),
    serverTsMs: Math.max(current.serverTsMs, snapshot.serverTsMs),
  };
}

export function createInspectionHistoryTiles(
  cameraIds: number[],
  historyByCameraId: Record<number, InspectionHistoryItem[]>,
) {
  const groups: InspectionHistoryTile[] = [];
  const historyItems = cameraIds
    .flatMap((cameraId) => historyByCameraId[cameraId] ?? [])
    .sort((left, right) => right.inspectResult.server_ts_ms - left.inspectResult.server_ts_ms);

  for (const item of historyItems) {
    const groupSource = item.inspectResult.inspection_id ? "inspection" : "frame";
    const groupId = item.inspectResult.inspection_id ?? item.frameId;

    const exactFrameGroup = groups.find(
      (group) =>
        group.groupSource === groupSource &&
        group.inspectionId === groupId &&
        group.results.some(
          (result) =>
            result.inspectResult.camera_id === item.inspectResult.camera_id && result.frameId === item.frameId,
        ),
    );
    if (exactFrameGroup) {
      replaceCameraResult(exactFrameGroup, item);
      continue;
    }

    const matchingCycleGroup = groups.find(
      (group) =>
        group.groupSource === groupSource &&
        group.inspectionId === groupId &&
        Math.abs(group.serverTsMs - item.inspectResult.server_ts_ms) <= INSPECTION_GROUP_WINDOW_MS &&
        group.results.every((result) => result.inspectResult.camera_id !== item.inspectResult.camera_id),
    );
    if (matchingCycleGroup) {
      matchingCycleGroup.results.push(item);
      matchingCycleGroup.result = resolveGroupResult(matchingCycleGroup.results);
      matchingCycleGroup.serverTsMs = Math.max(matchingCycleGroup.serverTsMs, item.inspectResult.server_ts_ms);
      continue;
    }

    groups.push({
      groupKey: createInspectionGroupKey(groupSource, groupId, item),
      inspectionId: groupId,
      groupSource,
      result: item.result,
      serverTsMs: item.inspectResult.server_ts_ms,
      results: [item],
    });
  }

  return groups
    .map((group) => ({
      ...group,
      results: [...group.results].sort((left, right) => left.inspectResult.camera_id - right.inspectResult.camera_id),
    }))
    .sort((left, right) => right.serverTsMs - left.serverTsMs)
    .slice(0, inspectionHistoryLimit);
}

function replaceCameraResult(group: InspectionHistoryTile, item: InspectionHistoryItem) {
  const resultIndex = group.results.findIndex(
    (result) => result.inspectResult.camera_id === item.inspectResult.camera_id && result.frameId === item.frameId,
  );
  if (resultIndex < 0) {
    return;
  }

  const current = group.results[resultIndex];
  if (!current.inspectResult.artifact_bundle_id && item.inspectResult.artifact_bundle_id) {
    group.results[resultIndex] = item;
  }
  group.result = resolveGroupResult(group.results);
  group.serverTsMs = Math.max(group.serverTsMs, item.inspectResult.server_ts_ms);
}

function resolveGroupResult(results: InspectionHistoryItem[]): "pass" | "fail" | "capture" {
  if (results.some((result) => result.result === "fail")) {
    return "fail";
  }
  if (results.some((result) => result.result === "pass")) {
    return "pass";
  }
  return "capture";
}

function createInspectionGroupKey(source: "inspection" | "frame", inspectionId: string, item: InspectionHistoryItem) {
  return `${source}:${inspectionId}:${item.inspectResult.server_ts_ms}:${item.inspectResult.camera_id}:${item.frameId}`;
}
