import { useSyncExternalStore } from "react";
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
    canSendReference,
    setJointViewIndex,
    handleSendReference,
    handleSelectCamera,
    selectedCameraId,
    roiPolygonsByCameraId,
    setRoiPolygonForCamera,
  } = useReferenceSetupController(onClose, initialJointViewIndex);
  const selectedSlot = cameraSlots.find((slot) => slot.cameraId === selectedCameraId);
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
            <label className="reference-setup__field">
              <span>Основной ракурс</span>
              <select
                value={jointViewIndex}
                onChange={(event) => setJointViewIndex(Number(event.target.value))}
              >
                {cameraSlots.map((slot) => (
                  <option
                    key={slot.cameraId}
                    value={slot.cameraId}
                  >
                    Камера {slot.cameraId}
                  </option>
                ))}
              </select>
            </label>

            <button
              className="reference-setup__button reference-setup__button--primary"
              type="button"
              disabled={!canSendReference}
              onClick={handleSendReference}
            >
              Задать эталон
            </button>
          </div>

          <div className="reference-setup__workspace">
            <div className="reference-setup__editor">
              {selectedSlot?.imageUrl && (
                <RoiContourEditor
                  imageUrl={selectedSlot.imageUrl}
                  points={roiPolygonsByCameraId[selectedCameraId] ?? []}
                  onChange={(points) => setRoiPolygonForCamera(selectedCameraId, points)}
                />
              )}
            </div>

            <div className="reference-setup__camera-list">
              {cameraSlots.map((slot) => (
                <button
                  key={slot.cameraId}
                  className={
                    slot.cameraId === selectedCameraId
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
