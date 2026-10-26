import { useMemo, useState } from "react";
import { InspectionHistoryModal } from "../InspectionHistoryModal";
import type { InspectionHistoryItem } from "../MainOverview/type";
import { createInspectionHistoryTiles, selectInspectionHistoryTile } from "./historyTiles";
import type { InspectionHistoryTile } from "./historyTiles";
import "./InspectionHistory.css";

type InspectionHistoryProps = {
  cameraIds: number[];
  historyByCameraId: Record<number, InspectionHistoryItem[]>;
  archiveHistoryState?: "idle" | "loading" | "loaded" | "error";
  onLoadArchivedHistory?: (cameraIds: number[]) => void;
  onCameraOpen?: (item: InspectionHistoryItem) => void;
};

export function InspectionHistory({
  cameraIds,
  historyByCameraId,
  archiveHistoryState = "idle",
  onLoadArchivedHistory,
  onCameraOpen,
}: InspectionHistoryProps) {
  const items = useMemo(
    () => createInspectionHistoryTiles(cameraIds, historyByCameraId),
    [cameraIds, historyByCameraId],
  );
  const [selection, setSelection] = useState<{
    snapshot: InspectionHistoryTile;
    historyItems: InspectionHistoryTile[];
  } | null>(null);
  // Retain the latest complete snapshot when the rolling history eventually evicts it.
  if (selection && selection.historyItems !== items) {
    setSelection({
      snapshot: selectInspectionHistoryTile(items, selection.snapshot) ?? selection.snapshot,
      historyItems: items,
    });
  }
  const selectedInspection = selection?.snapshot ?? null;
  const isLoadingArchive = archiveHistoryState === "loading";

  const selectInspection = (groupKey: string) => {
    const item = items.find((candidate) => candidate.groupKey === groupKey);
    if (!item) {
      return;
    }
    setSelection({ snapshot: item, historyItems: items });
  };

  const closeInspection = () => {
    setSelection(null);
  };

  return (
    <>
      <section
        className="inspection-history"
        aria-label="Последние инспекции"
      >
        <header className="inspection-history__header">
          <span>Последние инспекции ({items.length})</span>
          {onLoadArchivedHistory && (
            <button
              className="inspection-history__archive-btn"
              type="button"
              disabled={isLoadingArchive || cameraIds.length === 0}
              onClick={() => onLoadArchivedHistory(cameraIds)}
            >
              {isLoadingArchive ? "Загрузка..." : "Открыть архив"}
            </button>
          )}
        </header>
        <div className="inspection-history__tiles">
          {items.map((item) => (
            <button
              className="inspection-history__tile"
              data-result={item.result}
              key={item.groupKey}
              type="button"
              title={`Инспекция ${item.inspectionId}: ${
                item.result === "pass" ? "Годен" : item.result === "fail" ? "Брак" : "Съёмка"
              }`}
              onClick={() => selectInspection(item.groupKey)}
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
          historyItems={items}
          selectedGroupKey={selectedInspection.groupKey}
          onHistorySelect={selectInspection}
          onResultOpen={(item) => {
            closeInspection();
            onCameraOpen?.(item);
          }}
          onClose={closeInspection}
        />
      )}
    </>
  );
}
