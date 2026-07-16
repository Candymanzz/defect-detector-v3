import { useEffect, useMemo, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { InspectionHistoryModal } from "../InspectionHistoryModal";
import { inspectionHistoryLimit } from "../MainOverview/MainController";
import type { InspectionHistoryItem } from "../MainOverview/type";
import "./ArchiveHistoryViewer.css";

type ArchiveHistoryViewerProps = {
  cameraIds: number[];
  historyByCameraId: Record<number, InspectionHistoryItem[]>;
  onClose: () => void;
  onChanged?: () => void;
};

type ArchiveTile = {
  groupKey: string;
  inspectionId: string;
  result: "pass" | "fail" | "capture";
  serverTsMs: number;
  results: InspectionHistoryItem[];
};

export function ArchiveHistoryViewer({
  cameraIds,
  historyByCameraId,
  onClose,
  onChanged,
}: ArchiveHistoryViewerProps) {
  const [localHistory, setLocalHistory] = useState(historyByCameraId);
  const [selected, setSelected] = useState<ArchiveTile | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [statusError, setStatusError] = useState(false);

  useEffect(() => {
    setLocalHistory(historyByCameraId);
  }, [historyByCameraId]);

  const tiles = useMemo(() => createArchiveTiles(cameraIds, localHistory), [cameraIds, localHistory]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !selected) {
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose, selected]);

  const handleDeleteTile = async (tile: ArchiveTile) => {
    if (busyKey) {
      return;
    }
    const confirmed = window.confirm(
      `Удалить инспекцию ${tile.inspectionId} из архива (${tile.results.length} кадр.)?`,
    );
    if (!confirmed) {
      return;
    }

    setBusyKey(tile.groupKey);
    setStatusMessage(null);
    setStatusError(false);
    try {
      await Promise.all(
        tile.results.map((item) =>
          orchestratorApi.deleteFrameArchiveFrame(item.inspectResult.camera_id, item.frameId),
        ),
      );
      setLocalHistory((current) => removeTileFromHistory(current, tile));
      if (selected?.groupKey === tile.groupKey) {
        setSelected(null);
      }
      setStatusMessage(`Удалено: инспекция ${tile.inspectionId}`);
      onChanged?.();
    } catch (error) {
      setStatusError(true);
      setStatusMessage(errorMessage(error));
    } finally {
      setBusyKey(null);
    }
  };

  const handleClearAll = async () => {
    if (busyKey || tiles.length === 0) {
      return;
    }
    const confirmed = window.confirm("Удалить весь архив для выбранных камер?");
    if (!confirmed) {
      return;
    }

    setBusyKey("clear-all");
    setStatusMessage(null);
    setStatusError(false);
    try {
      const response = await orchestratorApi.clearFrameArchive(cameraIds);
      setLocalHistory({});
      setSelected(null);
      setStatusMessage(`Архив очищен (${response.deleted} кадров)`);
      onChanged?.();
    } catch (error) {
      setStatusError(true);
      setStatusMessage(errorMessage(error));
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <>
      <div
        className="modal-backdrop"
        onMouseDown={onClose}
      >
        <section
          aria-label="Архив кадров"
          aria-modal="true"
          className="modal archive-history-viewer"
          role="dialog"
          onMouseDown={(event) => event.stopPropagation()}
        >
          <header className="modal__header">
            <h2>Архив кадров</h2>
            <button
              aria-label="Закрыть"
              className="modal__close"
              type="button"
              onClick={onClose}
            >
              x
            </button>
          </header>

          <div className="archive-history-viewer__toolbar">
            <p className="archive-history-viewer__hint">
              Сортировка: сначала новые по дате и времени. При достижении лимита старые кадры
              перезаписываются.
            </p>
            <button
              className="archive-history-viewer__clear"
              type="button"
              disabled={busyKey !== null || tiles.length === 0}
              onClick={() => void handleClearAll()}
            >
              {busyKey === "clear-all" ? "Удаление..." : "Удалить всё"}
            </button>
          </div>

          {statusMessage && (
            <p
              className={
                statusError
                  ? "archive-history-viewer__status archive-history-viewer__status--error"
                  : "archive-history-viewer__status"
              }
            >
              {statusMessage}
            </p>
          )}

          <div className="archive-history-viewer__tiles">
            {tiles.map((tile) => (
              <article
                className="archive-history-viewer__tile"
                data-result={tile.result}
                key={tile.groupKey}
              >
                <button
                  className="archive-history-viewer__tile-open"
                  type="button"
                  title={`Inspection ${tile.inspectionId}`}
                  onClick={() => setSelected(tile)}
                >
                  <strong>{tile.inspectionId}</strong>
                  <span>{formatArchiveTime(tile.serverTsMs)}</span>
                  <em>
                    {tile.result === "pass" ? "Годен" : tile.result === "fail" ? "Брак" : "Съёмка"} ·{" "}
                    {tile.results.length} кам.
                  </em>
                </button>
                <button
                  className="archive-history-viewer__tile-delete"
                  type="button"
                  aria-label={`Удалить инспекцию ${tile.inspectionId}`}
                  disabled={busyKey !== null}
                  onClick={() => void handleDeleteTile(tile)}
                >
                  {busyKey === tile.groupKey ? "..." : "Удалить"}
                </button>
              </article>
            ))}
            {tiles.length === 0 && (
              <div className="archive-history-viewer__empty">В архиве пока нет кадров</div>
            )}
          </div>
        </section>
      </div>

      {selected && (
        <InspectionHistoryModal
          inspectionId={selected.inspectionId}
          results={selected.results}
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
}

function createArchiveTiles(cameraIds: number[], historyByCameraId: Record<number, InspectionHistoryItem[]>) {
  const groups = new Map<string, ArchiveTile>();

  for (const cameraId of cameraIds) {
    for (const item of historyByCameraId[cameraId] ?? []) {
      const inspectionId = item.inspectionId || item.frameId;
      const existing = groups.get(inspectionId);
      if (!existing) {
        groups.set(inspectionId, {
          groupKey: `archive:${inspectionId}:${item.inspectResult.server_ts_ms}`,
          inspectionId,
          result: item.result,
          serverTsMs: item.inspectResult.server_ts_ms,
          results: [item],
        });
        continue;
      }
      const sameCamera = existing.results.findIndex(
        (result) => result.inspectResult.camera_id === item.inspectResult.camera_id,
      );
      if (sameCamera >= 0) {
        existing.results[sameCamera] = item;
      } else {
        existing.results.push(item);
      }
      existing.result =
        existing.result === "fail" || item.result === "fail"
          ? "fail"
          : existing.result === "capture" || item.result === "capture"
            ? "capture"
            : "pass";
      existing.serverTsMs = Math.max(existing.serverTsMs, item.inspectResult.server_ts_ms);
    }
  }

  return [...groups.values()]
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

function removeTileFromHistory(
  historyByCameraId: Record<number, InspectionHistoryItem[]>,
  tile: ArchiveTile,
): Record<number, InspectionHistoryItem[]> {
  const next = { ...historyByCameraId };
  for (const item of tile.results) {
    const cameraId = item.inspectResult.camera_id;
    next[cameraId] = (next[cameraId] ?? []).filter((entry) => entry.frameId !== item.frameId);
  }
  return next;
}

function formatArchiveTime(epochMs: number) {
  if (!Number.isFinite(epochMs) || epochMs <= 0) {
    return "—";
  }
  return new Date(epochMs).toLocaleString("ru-RU", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}
