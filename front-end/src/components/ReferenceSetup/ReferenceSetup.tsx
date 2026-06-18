import { useSyncExternalStore } from "react";
import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { RoiContourEditor } from "../RoiContourEditor";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
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
    hasRequiredJointRoi,
    canSendAllReferences,
    handleSendAllReferences,
    handleSelectCamera,
    handleSelectJointRoi,
    selectedCameraId,
    selectedRoiMode,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    setJointRoiPolygon,
    setRoiPolygonForCamera,
  } = useReferenceSetupController(onClose, initialCameraId);
  const selectedSlot = cameraSlots.find((slot) => slot.cameraId === selectedCameraId);
  const editorKey = `${selectedRoiMode}-${selectedCameraId}`;
  const selectedEditorPoints =
    selectedRoiMode === "joint" ? jointRoiPolygon : (roiPolygonsByCameraId[selectedCameraId] ?? []);
  const storedReferenceImage = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImage(selectedCameraId),
    () => undefined,
  );

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
              <span>Камера joint ROI</span>
              <span className="reference-setup__readonly">
                Camera {jointCameraId} / view {jointViewIndex}
              </span>
            </label>

            <button
              className="reference-setup__button reference-setup__button--primary"
              type="button"
              disabled={!canSendAllReferences}
              onClick={handleSendAllReferences}
            >
              Задать эталон
            </button>
          </div>

          <div className="reference-setup__workspace">
            <div className="reference-setup__editor">
              {selectedSlot?.imageUrl && (
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
              )}
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
                    onClick={() => handleSelectCamera(slot.cameraId)}
                  >
                    <strong>Camera {slot.cameraId}</strong>
                    <span>{slot.frame ? "Кадр получен" : "Ожидание кадра"}</span>
                    <span>{roiPolygonsByCameraId[slot.cameraId]?.length >= 3 ? "ROI задан" : "ROI не задан"}</span>
                  </button>

                  {slot.cameraId === jointCameraId && (
                    <button
                      className={
                        selectedRoiMode === "joint"
                          ? "reference-setup__joint-trigger reference-setup__joint-trigger--active"
                          : "reference-setup__joint-trigger"
                      }
                      type="button"
                      onClick={handleSelectJointRoi}
                    >
                      Joint
                      <span>{hasRequiredJointRoi ? "ROI задан" : "ROI не задан"}</span>
                    </button>
                  )}
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
              {selectedRoiMode === "joint"
                ? `Редактируется joint ROI для Camera ${jointCameraId}`
                : hasRequiredJointRoi
                  ? `Joint ROI задан для Camera ${jointCameraId}`
                  : `Joint ROI для Camera ${jointCameraId} обязателен`}
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
