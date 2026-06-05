import { useSyncExternalStore } from "react";
import { Button } from "../../shared/ui/Button";
import "../ModalWrapper/ModalWrapper.css";
import "./ReferenceSetup.css";
import { RoiContourEditor } from "../RoiContourEditor";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { useReferenceSetupController } from "./ReferenceController";

type ReferenceSetupProps = {
  initialJointViewIndex: number | null;
  onClose: () => void;
};

export function ReferenceSetup({ onClose, initialJointViewIndex }: ReferenceSetupProps) {
  const {
    status,
    message,
    cameraSlots,
    jointViewIndex,
    hasSelectedCameraRoi,
    hasRequiredJointRoi,
    canSendReference,
    handleSendReference,
    handleSelectCamera,
    handleSelectJointRoi,
    selectedCameraId,
    selectedRoiMode,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    setJointRoiPolygon,
    setRoiPolygonForCamera,
  } = useReferenceSetupController(onClose, initialJointViewIndex);
  const selectedSlot = cameraSlots.find((slot) => slot.cameraId === selectedCameraId);
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
          <Button
            aria-label="Закрыть"
            className="modal__close"
            type="button"
            variant="ghost"
            onClick={onClose}
          >
            x
          </Button>
        </header>

        <div className="reference-setup__body">
          <div className="reference-setup__toolbar">
            <label className="reference-setup__field">
              <span>Камера joint ROI</span>
              <span className="reference-setup__readonly">Camera {jointViewIndex}</span>
            </label>

            <Button
              type="button"
              disabled={!canSendReference}
              variant="primary"
              onClick={handleSendReference}
            >
              Задать эталон
            </Button>
          </div>

          <div className="reference-setup__workspace">
            <div className="reference-setup__editor">
              {selectedSlot?.imageUrl && (
                <RoiContourEditor
                  imageUrl={selectedSlot.imageUrl}
                  points={selectedEditorPoints}
                  onChange={(points) => {
                    if (selectedRoiMode === "joint") {
                      setJointRoiPolygon(points);
                      return;
                    }

                    setRoiPolygonForCamera(selectedCameraId, points);
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

                  {slot.cameraId === 0 && (
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
              {storedReferenceImage ? `Эталон задан для Camera ${selectedCameraId}` : "Эталон еще не задан"}
            </p>
            <p className="reference-setup__roi-status">
              {hasSelectedCameraRoi
                ? `ROI задан для Camera ${selectedCameraId}`
                : `Задайте ROI-контур для Camera ${selectedCameraId}: минимум 3 точки`}
            </p>
            <p className="reference-setup__roi-status">
              {selectedRoiMode === "joint"
                ? "Редактируется joint ROI для Camera 0"
                : hasRequiredJointRoi
                  ? "Joint ROI задан для Camera 0"
                  : "Joint ROI для Camera 0 обязателен"}
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
