import { useState } from "react";
import { InspectionHistoryModal } from "../InspectionHistoryModal";
import type { InspectionHistoryItem } from "../MainOverview/type";
import "./InspectionHistory.css";

const INSPECTION_HISTORY_LIMIT = 20;
const INSPECTION_GROUP_WINDOW_MS = 15_000;

type InspectionHistoryProps = {
  cameraIds: number[];
  historyByCameraId: Record<number, InspectionHistoryItem[]>;
};

type InspectionHistoryTile = {
  groupKey: string;
  inspectionId: string;
  result: "pass" | "fail";
  serverTsMs: number;
  results: InspectionHistoryItem[];
};

export function InspectionHistory({ cameraIds, historyByCameraId }: InspectionHistoryProps) {
  const items = createInspectionHistoryTiles(cameraIds, historyByCameraId);
  const [selectedInspection, setSelectedInspection] = useState<{
    inspectionId: string;
    results: InspectionHistoryItem[];
  } | null>(null);

  return (
    <>
      <section
        className="inspection-history"
        aria-label="Последние инспекции"
      >
        <header>Inspection History</header>
        <div className="inspection-history__tiles">
          {items.map((item) => (
            <button
              className="inspection-history__tile"
              data-result={item.result}
              key={item.groupKey}
              type="button"
              title={`Inspection ${item.inspectionId}: ${item.result === "pass" ? "Годен" : "Брак"}`}
              onClick={() =>
                setSelectedInspection({
                  inspectionId: item.inspectionId,
                  results: item.results,
                })
              }
            >
              {item.inspectionId}
            </button>
          ))}
          {items.length === 0 && <div className="inspection-history__empty">Нет результатов инспекции</div>}
        </div>
      </section>

      {selectedInspection && (
        <InspectionHistoryModal
          inspectionId={selectedInspection.inspectionId}
          results={selectedInspection.results}
          onClose={() => setSelectedInspection(null)}
        />
      )}
    </>
  );
}

function createInspectionHistoryTiles(
  cameraIds: number[],
  historyByCameraId: Record<number, InspectionHistoryItem[]>,
) {
  const groups: InspectionHistoryTile[] = [];
  const historyItems = cameraIds
    .flatMap((cameraId) => historyByCameraId[cameraId] ?? [])
    .filter((item) => item.inspectResult.inspection_id !== undefined)
    .sort((left, right) => right.inspectResult.server_ts_ms - left.inspectResult.server_ts_ms);

  for (const item of historyItems) {
    const explicitInspectionId = item.inspectResult.inspection_id;
    if (!explicitInspectionId) {
      continue;
    }

    const exactFrameGroup = groups.find(
      (group) =>
        group.inspectionId === explicitInspectionId &&
        group.results.some(
          (result) =>
            result.inspectResult.camera_id === item.inspectResult.camera_id &&
            result.frameId === item.frameId,
        ),
    );
    if (exactFrameGroup) {
      replaceCameraResult(exactFrameGroup, item);
      continue;
    }

    const matchingCycleGroup = groups.find(
      (group) =>
        group.inspectionId === explicitInspectionId &&
        Math.abs(group.serverTsMs - item.inspectResult.server_ts_ms) <= INSPECTION_GROUP_WINDOW_MS &&
        group.results.every((result) => result.inspectResult.camera_id !== item.inspectResult.camera_id),
    );
    if (matchingCycleGroup) {
      matchingCycleGroup.results.push(item);
      matchingCycleGroup.result =
        matchingCycleGroup.result === "fail" || item.result === "fail" ? "fail" : "pass";
      matchingCycleGroup.serverTsMs = Math.max(
        matchingCycleGroup.serverTsMs,
        item.inspectResult.server_ts_ms,
      );
      continue;
    }

    groups.push({
      groupKey: createInspectionGroupKey(explicitInspectionId, item),
      inspectionId: explicitInspectionId,
      result: item.result,
      serverTsMs: item.inspectResult.server_ts_ms,
      results: [item],
    });
  }

  return groups
    .map((group) => ({
      ...group,
      results: [...group.results].sort(
        (left, right) => left.inspectResult.camera_id - right.inspectResult.camera_id,
      ),
    }))
    .sort((left, right) => right.serverTsMs - left.serverTsMs)
    .slice(0, INSPECTION_HISTORY_LIMIT);
}

function replaceCameraResult(group: InspectionHistoryTile, item: InspectionHistoryItem) {
  const resultIndex = group.results.findIndex(
    (result) =>
      result.inspectResult.camera_id === item.inspectResult.camera_id &&
      result.frameId === item.frameId,
  );
  if (resultIndex < 0) {
    return;
  }

  const current = group.results[resultIndex];
  if (!current.inspectResult.artifact_bundle_id && item.inspectResult.artifact_bundle_id) {
    group.results[resultIndex] = item;
  }
  group.result = group.results.some((result) => result.result === "fail") ? "fail" : "pass";
  group.serverTsMs = Math.max(group.serverTsMs, item.inspectResult.server_ts_ms);
}

function createInspectionGroupKey(inspectionId: string, item: InspectionHistoryItem) {
  return `${inspectionId}:${item.inspectResult.server_ts_ms}:${item.inspectResult.camera_id}:${item.frameId}`;
}
