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
    canSaveFpZones,
    hasStoredReferenceForActiveGroup,
    isNewReferenceMode,
    handleCaptureNewReferenceFrames,
    handleSendAllReferences,
    handleSaveFpZones,
    handleSelectCamera,
    handleSelectJointRoi,
    handleUseArchivedReference,
    selectedCameraId,
    selectedRoiMode,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    setJointRoiPolygon,
    setRoiPolygonForCamera,
    fpZones,
    setFpZones,
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
  const fpZoneSlot = cameraSlots.find((slot) => slot.cameraId === jointCameraId) ?? selectedSlot;

  return (
    <div
      className="modal-backdrop"
      onMouseDown={onClose}
    >
      <section
        aria-label="Р—Р°РґР°РЅРёРµ СЌС‚Р°Р»РѕРЅР°"
        aria-modal="true"
        className="modal reference-setup"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header">
          <h2>Р—Р°РґР°РЅРёРµ СЌС‚Р°Р»РѕРЅР°</h2>
          <button
            aria-label="Р—Р°РєСЂС‹С‚СЊ"
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
                    Group {groupIndex + 1}
                    <span>Cameras {groupCameraIds.join(", ")}</span>
                  </button>
                ))}
              </div>
            )}
            <label className="reference-setup__field">
                <span>РљР°РјРµСЂР° joint ROI (РЅРµРѕР±СЏР·Р°С‚РµР»СЊРЅРѕ)</span>
              <span className="reference-setup__readonly">
                Camera {jointCameraId} / view {jointViewIndex}
              </span>
            </label>

            <button
              className={
                isFpZoneMode
                  ? "reference-setup__button reference-setup__button--fp reference-setup__button--active"
                  : "reference-setup__button reference-setup__button--fp"
              }
              type="button"
              disabled={!fpZoneSlot?.imageUrl}
              onClick={() => setIsFpZoneMode(true)}
            >
              FP zones ({fpZones.length})
            </button>

            {isFpZoneMode && (
              <button
                className="reference-setup__button reference-setup__button--fp-save"
                type="button"
                disabled={!canSaveFpZones}
                onClick={handleSaveFpZones}
              >
                РЎРѕС…СЂР°РЅРёС‚СЊ FP zones
              </button>
            )}

            {hasStoredReferenceForActiveGroup && (
              <Button
                className="reference-setup__button"
                onClick={handleCaptureNewReferenceFrames}
              >
                Р—Р°РґР°С‚СЊ РЅРѕРІС‹Р№ СЌС‚Р°Р»РѕРЅ
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
                  ? `свежие кадры / Cameras ${activeCameraIds.join(", ")} / контуры нужно задать заново`
                  : activeArchive
                    ? `старый эталон от ${formatArchiveTime(activeArchive.createdAtMs)} / Cameras ${activeArchive.cameraIds.join(", ")}`
                    : `текущий эталон / Cameras ${activeCameraIds.join(", ")}`}
              </span>
            </div>
          )}

          <div className="reference-setup__workspace">
            <div className="reference-setup__editor">
              {isFpZoneMode && fpZoneSlot?.imageUrl ? (
                <FpZoneEditor
                  imageUrl={fpZoneSlot.imageUrl}
                  zones={fpZones}
                  disabled={status.state !== "open"}
                  onChange={setFpZones}
                />
              ) : selectedSlot?.imageUrl ? (
                <RoiContourEditor
                  key={editorKey}
                  imageUrl={selectedSlot.imageUrl}
                  points={selectedEditorPoints}
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
                      setIsFpZoneMode(false);
                      handleSelectCamera(slot.cameraId);
                    }}
                  >
                    <strong>Camera {slot.cameraId}</strong>
                    <span>{slot.frame ? "РљР°РґСЂ РїРѕР»СѓС‡РµРЅ" : "РћР¶РёРґР°РЅРёРµ РєР°РґСЂР°"}</span>
                    <span>{roiPolygonsByCameraId[slot.cameraId]?.length >= 3 ? "ROI Р·Р°РґР°РЅ" : "ROI РЅРµ Р·Р°РґР°РЅ"}</span>
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
                          ? "ROI Р·Р°РґР°РЅ"
                          : "Р’С‹Р±СЂР°РЅР° РєР°РјРµСЂР°"
                        : "Р’С‹Р±СЂР°С‚СЊ"}
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
    <section className="reference-setup__archive" aria-label="Archive references">
      <header className="reference-setup__archive-header">
        <h3>РЎС‚Р°СЂС‹Рµ СЌС‚Р°Р»РѕРЅС‹</h3>
        {selectedArchive && (
          <Button
            className="reference-setup__button"
            onClick={() => onUse(selectedArchive.id)}
          >
            РСЃРїРѕР»СЊР·РѕРІР°С‚СЊ РІС‹Р±СЂР°РЅРЅС‹Р№
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
                alt={`Reference ${archive.cameraIds.join(", ")}`}
              />
              <span>{formatArchiveTime(archive.createdAtMs)}</span>
              <strong>Cameras {archive.cameraIds.join(", ")}</strong>
              {archive.id === activeArchiveId && <em>Р’ СЂР°Р±РѕС‚Рµ</em>}
              <button
                className="reference-setup__archive-delete"
                type="button"
                aria-label="РЈРґР°Р»РёС‚СЊ СЃС‚Р°СЂС‹Р№ СЌС‚Р°Р»РѕРЅ"
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
  const mediaStyle = {
    "--archive-aspect": image.frame.width / image.frame.height,
  } as CSSProperties;

  return (
    <figure className="reference-setup__archive-frame">
      <figcaption>Camera {image.cameraId}</figcaption>
      <div
        className="reference-setup__archive-frame-media"
        style={mediaStyle}
      >
        <img
          src={image.imageUrl}
          alt={`Camera ${image.cameraId}`}
        />
        <div className="reference-setup__archive-frame-empty">РљР°РґСЂ РЅРµ Р·Р°РіСЂСѓР¶РµРЅ</div>
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
