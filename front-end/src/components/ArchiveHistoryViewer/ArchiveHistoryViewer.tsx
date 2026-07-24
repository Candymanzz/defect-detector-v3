import { useEffect, useMemo, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { InspectionHistoryModal } from "../InspectionHistoryModal";
import type { InspectionHistoryItem } from "../MainOverview/type";
import { createArchiveTiles } from "./archiveTiles";
import type { ArchiveTile } from "./archiveTiles";
import "./ArchiveHistoryViewer.css";

type ArchiveHistoryViewerProps = {
  cameraIds: number[];
  historyByCameraId: Record<number, InspectionHistoryItem[]>;
  onClose: () => void;
  onChanged?: () => void | Promise<void>;
};

export function ArchiveHistoryViewer({
  cameraIds,
  historyByCameraId,
  onClose,
  onChanged,
}: ArchiveHistoryViewerProps) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [statusError, setStatusError] = useState(false);

  const tiles = useMemo(() => createArchiveTiles(cameraIds, historyByCameraId), [cameraIds, historyByCameraId]);
  const selected = selectedKey ? tiles.find((tile) => tile.groupKey === selectedKey) ?? null : null;

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
      const results = await Promise.allSettled(
        tile.results.map((item) =>
          orchestratorApi.deleteFrameArchiveFrame(item.inspectResult.camera_id, item.frameId),
        ),
      );
      const deletedCount = results.filter((result) => result.status === "fulfilled").length;
      const failedCount = results.length - deletedCount;
      if (deletedCount > 0 && selectedKey === tile.groupKey) {
        setSelectedKey(null);
      }
      await onChanged?.();
      if (failedCount > 0) {
        setStatusError(true);
        setStatusMessage(`Удалено ${deletedCount} из ${results.length} кадров. Не удалось удалить: ${failedCount}`);
      } else {
        setStatusMessage(`Удалено: инспекция ${tile.inspectionId}`);
      }
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
      setSelectedKey(null);
      setStatusMessage(`Архив очищен (${response.deleted} кадров)`);
      await onChanged?.();
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
                  title={`Инспекция ${tile.inspectionId}`}
                  onClick={() => setSelectedKey(tile.groupKey)}
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
          onClose={() => setSelectedKey(null)}
        />
      )}
    </>
  );
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
