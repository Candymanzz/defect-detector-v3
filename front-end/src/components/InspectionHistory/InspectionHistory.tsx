import { useState } from "react";
import { InspectionHistoryModal } from "../InspectionHistoryModal";
import type { InspectionHistoryItem } from "../MainOverview/type";
import "./InspectionHistory.css";

import { inspectionHistoryLimit } from "../MainOverview/MainController";
const INSPECTION_GROUP_WINDOW_MS = 15_000;

type InspectionHistoryProps = {
  cameraIds: number[];
  historyByCameraId: Record<number, InspectionHistoryItem[]>;
  archiveHistoryState?: "idle" | "loading" | "loaded" | "error";
  archiveHistoryMessage?: string | null;
  onLoadArchivedHistory?: (cameraIds: number[]) => void;
};

type InspectionHistoryTile = {
  groupKey: string;
  inspectionId: string;
  groupSource: "inspection" | "frame" | "bucket";
  result: "pass" | "fail" | "capture";
  serverTsMs: number;
  results: InspectionHistoryItem[];
};

export function InspectionHistory({
  cameraIds,
  historyByCameraId,
  archiveHistoryState = "idle",
  archiveHistoryMessage = null,
  onLoadArchivedHistory,
}: InspectionHistoryProps) {
  const items = createInspectionHistoryTiles(cameraIds, historyByCameraId);
  const [selectedInspection, setSelectedInspection] = useState<{
    inspectionId: string;
    results: InspectionHistoryItem[];
  } | null>(null);
  const isLoadingArchive = archiveHistoryState === "loading";

  return (
    <>
      <section
        className="inspection-history"
        aria-label="Последние инспекции"
      >
        <header className="inspection-history__header">
          <span>Последние инспекции</span>
          {onLoadArchivedHistory && (
            <button
              className="inspection-history__archive-btn"
              type="button"
              disabled={isLoadingArchive || cameraIds.length === 0}
              onClick={() => onLoadArchivedHistory(cameraIds)}
            >
              {isLoadingArchive ? "Загрузка..." : "Загрузить архив"}
            </button>
          )}
        </header>
        {archiveHistoryMessage && (
          <p
            className={
              archiveHistoryState === "error"
                ? "inspection-history__archive-status inspection-history__archive-status--error"
                : "inspection-history__archive-status"
            }
          >
            {archiveHistoryMessage}
          </p>
        )}
        <div className="inspection-history__tiles">
          {items.map((item) => (
            <button
              className="inspection-history__tile"
              data-result={item.result}
              key={item.groupKey}
              type="button"
              title={`Inspection ${item.inspectionId}: ${
                item.result === "pass" ? "Годен" : item.result === "fail" ? "Брак" : "Съёмка"
              }`}
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

function createInspectionHistoryTiles(cameraIds: number[], historyByCameraId: Record<number, InspectionHistoryItem[]>) {
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
      matchingCycleGroup.result =
        matchingCycleGroup.result === "fail" || item.result === "fail"
          ? "fail"
          : matchingCycleGroup.result === "capture" || item.result === "capture"
            ? "capture"
            : "pass";
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
  group.result = group.results.some((result) => result.result === "fail")
    ? "fail"
    : group.results.some((result) => result.result === "capture")
      ? "capture"
      : "pass";
  group.serverTsMs = Math.max(group.serverTsMs, item.inspectResult.server_ts_ms);
}

function createInspectionGroupKey(source: "inspection" | "frame", inspectionId: string, item: InspectionHistoryItem) {
  return `${source}:${inspectionId}:${item.inspectResult.server_ts_ms}:${item.inspectResult.camera_id}:${item.frameId}`;
}
