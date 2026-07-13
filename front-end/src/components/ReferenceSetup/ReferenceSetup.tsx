import { useState, useSyncExternalStore } from "react";
import type { CSSProperties, MouseEvent } from "react";
import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { RoiContourEditor } from "../RoiContourEditor";
import { FpZoneEditor } from "../FpZoneEditor";
import {
  deleteArchivedReferenceGroup,
  getArchivedReferenceGroups,
  getReferenceImage,
  subscribeReferenceImages,
} from "../../shared/referenceImages";
import type { ArchivedReferenceGroup } from "../../shared/referenceImages";
import { Button } from "../../shared/ui/Button";
import { useReferenceSetupController } from "./ReferenceController";

type ReferenceSetupProps = {
  initialCameraId: number | null;
  onClose: () => void;
};

export function ReferenceSetup({ onClose, initialCameraId }: ReferenceSetupProps) {
  const {
    status,
    cameraSlots,
    cameraGroups,
    activeGroupIndex,
    setActiveGroupIndex,
    jointViewIndex,
    jointCameraId,
    hasJointRoi,
    canSendAllReferences,
    hasStoredReferenceForActiveGroup,
    isNewReferenceMode,
    handleCaptureNewReferenceFrames,
    handleSendAllReferences,
    handleSelectCamera,
    handleSelectJointRoi,
    handleUseArchivedReference,
    selectedCameraId,
    selectedRoiMode,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    setJointRoiPolygon,
    setRoiPolygonForCamera,
    fpZonesByCameraId,
    setFpZonesForCameraId,
  } = useReferenceSetupController(onClose, initialCameraId);
  const [isFpZoneMode, setIsFpZoneMode] = useState(false);
  const [selectedArchiveId, setSelectedArchiveId] = useState<string | null>(null);
  const selectedSlot = cameraSlots.find((slot) => slot.cameraId === selectedCameraId);
  const editorKey = `${selectedRoiMode}-${selectedCameraId}`;
  const selectedEditorPoints =
    selectedRoiMode === "joint" ? jointRoiPolygon : (roiPolygonsByCameraId[selectedCameraId] ?? []);
  const archivedReferences = useSyncExternalStore(
    subscribeReferenceImages,
    getArchivedReferenceGroups,
    () => [],
  );
  const activeCameraIds = cameraSlots.map((slot) => slot.cameraId);
  const activeGroupKey = createCameraGroupKey(activeCameraIds);
  const activeGroupArchivedReferences = archivedReferences.filter(
    (archive) => createCameraGroupKey(archive.cameraIds) === activeGroupKey,
  );
  const activeReferenceKey = useSyncExternalStore(
    subscribeReferenceImages,
    () => createActiveReferenceKey(activeCameraIds),
    () => "",
  );
  const selectedArchive =
    activeGroupArchivedReferences.find((referenceGroup) => referenceGroup.id === selectedArchiveId) ??
    activeGroupArchivedReferences[0];
  const activeArchive = activeGroupArchivedReferences.find(
    (archive) => createArchiveReferenceKey(archive) === activeReferenceKey,
  );
  const fpZoneSlot = selectedSlot ?? cameraSlots[0];
  const selectedFpZones = fpZoneSlot ? (fpZonesByCameraId[fpZoneSlot.cameraId] ?? []) : [];

  return (
    <div
      className="modal-backdrop"
      onMouseDown={onClose}
    >
      <section
        aria-label="Задание эталона"
        aria-modal="true"
        className="modal reference-setup"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>Задание эталона</h2>
          <button
            aria-label="Закрыть"
            className="modal__close"
            type="button"
            onClick={onClose}
          >
            x
          </button>
        </header>

        <div className="reference-setup__body">
          <div className="reference-setup__toolbar">
            {cameraGroups.length > 1 && (
              <div className="reference-setup__group-switch" role="tablist" aria-label="Camera groups">
                {cameraGroups.map((groupCameraIds, groupIndex) => (
                  <button
                    key={groupCameraIds.join("-")}
                    aria-selected={groupIndex === activeGroupIndex}
                    className={
                      groupIndex === activeGroupIndex
                        ? "reference-setup__group-tab reference-setup__group-tab--active"
                        : "reference-setup__group-tab"
                    }
                    role="tab"
                    type="button"
                    onClick={() => setActiveGroupIndex(groupIndex)}
                  >
                    Группа {groupIndex + 1}
                    <span>Камеры {groupCameraIds.join(", ")}</span>
                  </button>
                ))}
              </div>
            )}
            <label className="reference-setup__field">
                <span>Камера joint ROI (необязательно)</span>
              <span className="reference-setup__readonly">
                Камера {jointCameraId} / вид {jointViewIndex}
              </span>
            </label>

            <button
              className={
                isFpZoneMode
                  ? "reference-setup__button reference-setup__button--fp reference-setup__button--active"
                  : "reference-setup__button reference-setup__button--fp"
              }
              type="button"
              aria-pressed={isFpZoneMode}
              disabled={!fpZoneSlot?.imageUrl}
              title={isFpZoneMode ? "Вернуться к редактированию ROI" : "Редактировать исключающие зоны"}
              onClick={() => setIsFpZoneMode((current) => !current)}
            >
              Исключающие зоны ({selectedFpZones.length})
            </button>

            {isFpZoneMode && (
              <button
                className="reference-setup__button"
                type="button"
                onClick={() => setIsFpZoneMode(false)}
              >
                Редактировать ROI
              </button>
            )}

            {hasStoredReferenceForActiveGroup && (
              <Button
                className="reference-setup__button"
                onClick={handleCaptureNewReferenceFrames}
              >
                Задать новый эталон
              </Button>
            )}

            <Button
              className="reference-setup__button"
              disabled={!canSendAllReferences}
              onClick={handleSendAllReferences}
            >
              {isNewReferenceMode ? "Сохранить новый эталон" : "Задать эталон"}
            </Button>
          </div>

          {(activeReferenceKey || isNewReferenceMode) && (
            <div
              className="reference-setup__active-reference"
              data-source={isNewReferenceMode ? "new" : activeArchive ? "archive" : "current"}
            >
              <strong>{isNewReferenceMode ? "Новый эталон" : "В работе"}</strong>
              <span>
                {isNewReferenceMode
                  ? `свежие кадры / камеры ${activeCameraIds.join(", ")} / контуры нужно задать заново`
                  : activeArchive
                    ? `старый эталон от ${formatArchiveTime(activeArchive.createdAtMs)} / камеры ${activeArchive.cameraIds.join(", ")}`
                    : `текущий эталон / камеры ${activeCameraIds.join(", ")}`}
              </span>
            </div>
          )}

          <div className="reference-setup__workspace">
            <div className="reference-setup__editor">
              {isFpZoneMode && fpZoneSlot?.imageUrl ? (
                <FpZoneEditor
                  imageUrl={fpZoneSlot.imageUrl}
                  roiPoints={roiPolygonsByCameraId[fpZoneSlot.cameraId] ?? []}
                  zones={selectedFpZones}
                  disabled={status.state !== "open"}
                  onChange={(zones) => setFpZonesForCameraId(fpZoneSlot.cameraId, zones)}
                />
              ) : selectedSlot?.imageUrl ? (
                <RoiContourEditor
                  key={editorKey}
                  imageUrl={selectedSlot.imageUrl}
                  points={selectedEditorPoints}
                  exclusionZones={fpZonesByCameraId[selectedSlot.cameraId] ?? []}
                  onChange={(points) => {
                    if (selectedRoiMode === "joint") {
                      setJointRoiPolygon(points);
                      return;
                    }

                    setRoiPolygonForCamera(selectedSlot.cameraId, points);
                  }}
                />
              ) : null}
            </div>

            <div className="reference-setup__camera-list">
              {cameraSlots.map((slot) => (
                <div
                  key={slot.cameraId}
                  className="reference-setup__camera-row"
                >
                  <button
                    className={
                      slot.cameraId === selectedCameraId && selectedRoiMode === "interest"
                        ? "reference-setup__slot reference-setup__slot--active"
                        : "reference-setup__slot"
                    }
                    type="button"
                    onClick={() => {
                      handleSelectCamera(slot.cameraId);
                    }}
                  >
                    <strong>Камера {slot.cameraId}</strong>
                    <span>{slot.frame ? "Кадр получен" : "Ожидание кадра"}</span>
                    <span>{roiPolygonsByCameraId[slot.cameraId]?.length >= 3 ? "ROI задан" : "ROI не задан"}</span>
                    <span>Искл. зон: {fpZonesByCameraId[slot.cameraId]?.length ?? 0}</span>
                  </button>

                  <button
                    className={
                      slot.cameraId === jointCameraId && selectedRoiMode === "joint"
                        ? "reference-setup__joint-trigger reference-setup__joint-trigger--active"
                        : "reference-setup__joint-trigger"
                    }
                    type="button"
                    onClick={() => {
                      setIsFpZoneMode(false);
                      handleSelectJointRoi(slot.cameraId);
                    }}
                  >
                    Joint
                    <span>
                      {slot.cameraId === jointCameraId
                        ? hasJointRoi
                          ? "ROI задан"
                          : "Выбрана камера"
                        : "Выбрать"}
                    </span>
                  </button>
                </div>
              ))}
            </div>
          </div>

          <ReferenceArchive
            archivedReferences={activeGroupArchivedReferences}
            activeArchiveId={activeArchive?.id}
            selectedArchive={selectedArchive}
            onDelete={(archiveId) => {
              deleteArchivedReferenceGroup(archiveId);
              if (selectedArchiveId === archiveId) {
                setSelectedArchiveId(null);
              }
            }}
            onSelect={setSelectedArchiveId}
            onUse={handleUseArchivedReference}
          />
        </div>
      </section>
    </div>
  );
}

function ReferenceArchive({
  archivedReferences,
  activeArchiveId,
  selectedArchive,
  onDelete,
  onSelect,
  onUse,
}: {
  archivedReferences: ArchivedReferenceGroup[];
  activeArchiveId?: string;
  selectedArchive?: ArchivedReferenceGroup;
  onDelete: (archiveId: string) => void;
  onSelect: (archiveId: string) => void;
  onUse: (archiveId: string) => void;
}) {
  if (archivedReferences.length === 0) {
    return null;
  }

  return (
    <section className="reference-setup__archive" aria-label="Старые эталоны">
      <header className="reference-setup__archive-header">
        <h3>Старые эталоны</h3>
        {selectedArchive && (
          <Button
            className="reference-setup__button"
            onClick={() => onUse(selectedArchive.id)}
          >
            Использовать выбранный
          </Button>
        )}
      </header>

      <div className="reference-setup__archive-layout">
        <div className="reference-setup__archive-tiles">
          {archivedReferences.map((archive) => (
            <article
              key={archive.id}
              className="reference-setup__archive-tile"
              data-active={archive.id === selectedArchive?.id}
              data-in-use={archive.id === activeArchiveId}
              role="button"
              tabIndex={0}
              onClick={() => onSelect(archive.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelect(archive.id);
                }
              }}
            >
              <img
                src={archive.images[0]?.imageUrl}
                alt={`Эталон камер ${archive.cameraIds.join(", ")}`}
              />
              <span>{formatArchiveTime(archive.createdAtMs)}</span>
              <strong>Камеры {archive.cameraIds.join(", ")}</strong>
              {archive.id === activeArchiveId && <em>В работе</em>}
              <button
                className="reference-setup__archive-delete"
                type="button"
                aria-label="Удалить старый эталон"
                onClick={(event: MouseEvent<HTMLButtonElement>) => {
                  event.stopPropagation();
                  onDelete(archive.id);
                }}
              >
                x
              </button>
            </article>
          ))}
        </div>

        {selectedArchive && (
          <div className="reference-setup__archive-preview">
            {selectedArchive.images.map((image) => (
              <ArchiveImage
                key={image.cameraId}
                image={image}
              />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function ArchiveImage({ image }: { image: ArchivedReferenceGroup["images"][number] }) {
  const roiPoints = image.roiPoints.map((point) => `${point.x},${point.y}`).join(" ");
  const jointRoiPoints = image.jointRoiPoints?.map((point) => `${point.x},${point.y}`).join(" ");
  const fpZonePoints = image.fpZones
    ?.filter((zone) => zone.points_norm_heatmap.length >= 3)
    .map((zone, index) => ({
      key: zone.id ?? index,
      points: zone.points_norm_heatmap.map((point) => `${point.x},${point.y}`).join(" "),
    }));
  const mediaStyle = {
    "--archive-aspect": image.frame.width / image.frame.height,
  } as CSSProperties;

  return (
    <figure className="reference-setup__archive-frame">
      <figcaption>Камера {image.cameraId}</figcaption>
      <div
        className="reference-setup__archive-frame-media"
        style={mediaStyle}
      >
        <img
          src={image.imageUrl}
          alt={`Камера ${image.cameraId}`}
        />
        <div className="reference-setup__archive-frame-empty">Кадр не загружен</div>
        <svg
          aria-hidden="true"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
        >
          {image.roiPoints.length >= 3 && <polygon points={roiPoints} />}
          {image.jointRoiPoints && image.jointRoiPoints.length >= 3 && (
            <polygon
              className="reference-setup__archive-joint"
              points={jointRoiPoints}
            />
          )}
          {fpZonePoints?.map((zone) => (
            <polygon
              key={zone.key}
              className="reference-setup__archive-fp-zone"
              points={zone.points}
            />
          ))}
        </svg>
      </div>
    </figure>
  );
}

function formatArchiveTime(createdAtMs: number) {
  return new Date(createdAtMs).toLocaleTimeString();
}

function createActiveReferenceKey(cameraIds: number[]) {
  return cameraIds
    .map((cameraId) => {
      const referenceImage = getReferenceImage(cameraId);
      return referenceImage ? createReferenceImageKey(cameraId, referenceImage) : "";
    })
    .filter(Boolean)
    .join("|");
}

function createCameraGroupKey(cameraIds: number[]) {
  return [...cameraIds].sort((left, right) => left - right).join(",");
}

function createArchiveReferenceKey(archive: ArchivedReferenceGroup) {
  return archive.images.map((image) => createReferenceImageKey(image.cameraId, image)).join("|");
}

function createReferenceImageKey(
  cameraId: number,
  referenceImage: { frame: { frame_id: string | number }; imageUrl: string },
) {
  return `${cameraId}:${referenceImage.frame.frame_id}:${referenceImage.imageUrl}`;
}
