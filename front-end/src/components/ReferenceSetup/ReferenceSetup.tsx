import { useState, useSyncExternalStore } from "react";
import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { RoiContourEditor } from "../RoiContourEditor";
import { FpZoneEditor } from "../FpZoneEditor";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { Button } from "../../shared/ui/Button";
import { useReferenceSetupController } from "./ReferenceController";

type ReferenceSetupProps = {
  initialCameraId: number | null;
  onClose: () => void;
};

export function ReferenceSetup({ onClose, initialCameraId }: ReferenceSetupProps) {
  const {
    status,
    message,
    cameraSlots,
    cameraGroups,
    activeGroupIndex,
    setActiveGroupIndex,
    jointViewIndex,
    jointCameraId,
    hasSelectedCameraRoi,
    hasJointRoi,
    canSendAllReferences,
    canSaveFpZones,
    handleSendAllReferences,
    handleSaveFpZones,
    handleSelectCamera,
    handleSelectJointRoi,
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
  const selectedSlot = cameraSlots.find((slot) => slot.cameraId === selectedCameraId);
  const editorKey = `${selectedRoiMode}-${selectedCameraId}`;
  const selectedEditorPoints =
    selectedRoiMode === "joint" ? jointRoiPolygon : (roiPolygonsByCameraId[selectedCameraId] ?? []);
  const storedReferenceImage = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImage(selectedCameraId),
    () => undefined,
  );
  const fpZoneSlot = cameraSlots.find((slot) => slot.cameraId === jointCameraId) ?? selectedSlot;

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
                    Group {groupIndex + 1}
                    <span>Cameras {groupCameraIds.join(", ")}</span>
                  </button>
                ))}
              </div>
            )}
            <label className="reference-setup__field">
                <span>Камера joint ROI (необязательно)</span>
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
                Сохранить FP zones
              </button>
            )}

            <Button
              className="reference-setup__button"
              disabled={!canSendAllReferences}
              onClick={handleSendAllReferences}
            >
              Задать эталон
            </Button>
          </div>

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
                    <span>{slot.frame ? "Кадр получен" : "Ожидание кадра"}</span>
                    <span>{roiPolygonsByCameraId[slot.cameraId]?.length >= 3 ? "ROI задан" : "ROI не задан"}</span>
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

          <div className="reference-setup__info">
            <p className="reference-setup__reference-status">
              {storedReferenceImage ? `Эталон задан для Camera ${selectedCameraId}` : "Эталон ещё не задан"}
            </p>
            <p className="reference-setup__roi-status">
              {hasSelectedCameraRoi
                ? `ROI задан для Camera ${selectedCameraId}`
                : `Задайте ROI-контур для Camera ${selectedCameraId}: минимум 3 точки`}
            </p>
            <p className="reference-setup__roi-status">
              {fpZones.length === 0
                ? "FP zones не заданы (необязательно)"
                : fpZones.every((zone) => zone.points_norm_heatmap.length >= 3)
                  ? `FP zones заданы: ${fpZones.length}`
                  : "Завершите контур FP zone: минимум 3 точки"}
            </p>
            <p className="reference-setup__roi-status">
              {selectedRoiMode === "joint"
                ? `Редактируется joint ROI для Camera ${jointCameraId}`
                : hasJointRoi
                  ? `Joint ROI задан для Camera ${jointCameraId}`
                  : "Joint ROI не задан (необязательно)"}
            </p>
            <p className="reference-setup__hint">
              Статус: {status.state}
              <br />
              {message}
            </p>
          </div>
        </div>
      </section>
    </div>
  );
}
