import { useEffect, useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { StatusCard } from "../../shared/ui/StatusCard";
import {
  createMainOverviewErrorData,
  createCameraCards,
  createSelectedCamera,
  FALLBACK_CAMERA_IDS,
  getModalCameraImageUrl,
  INITIAL_BACKEND_STATUS,
  loadMainOverviewData,
} from "./MainController";
import type { BackendStatus, SelectedCamera } from "./MainController";
import "./MainOverview.css";


export function MainOverview() {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);

  const backendReady = backendStatus.state === "ready";
  const cameraCards = createCameraCards(cameraIds, backendReady);
  const modalCameraImageUrl = getModalCameraImageUrl(selectedCamera, backendReady);

  useEffect(() => {
    let isActive = true;

    loadMainOverviewData()
      .catch(createMainOverviewErrorData)
      .then(({ backendStatus: nextBackendStatus, cameraIds: nextCameraIds }) => {
        if (!isActive) {
          return;
        }

        setBackendStatus(nextBackendStatus);
        setCameraIds(nextCameraIds);
      });

    return () => {
      isActive = false;
    };
  }, []);

  return (
    <section className="camera-overview" aria-label="Camera frames">
      <div className="backend-status-row">
        <span>Backend</span>
        <strong data-status={backendStatus.state}>{backendStatus.text}</strong>
      </div>

      <div className="camera-grid">
        {cameraCards.map((camera) => (
          <StatusCard
            key={camera.cameraId}
            cameraId={camera.cameraId}
            objectName={camera.objectName}
            imageUrl={camera.imageUrl}
            onClick={() => setSelectedCamera(createSelectedCamera(camera))}
          />
        ))}
      </div>

      {selectedCamera && (
        <ModalWrapper
          isOpen
          cameraImageUrl={modalCameraImageUrl}
          title={`${selectedCamera.objectName} / Camera ${selectedCamera.cameraId}`}
          onClose={() => setSelectedCamera(null)}
        />
      )}
    </section>
  );
}
