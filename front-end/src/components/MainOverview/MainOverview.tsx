import { useEffect, useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { ServerStream } from "../ServerStream";
import { orchestratorApi } from "../../shared/api";
import { orchestratorWs } from "../../shared/ws";
import { StatusCard } from "../../shared/ui/StatusCard";
import {
  createMainOverviewErrorData,
  createCameraCards,
  createSelectedCamera,
  createWsFrameImageUrl,
  FALLBACK_CAMERA_IDS,
  INITIAL_BACKEND_STATUS,
  loadMainOverviewData,
} from "./MainController";
import type { BackendStatus, CameraImageUrlsById, SelectedCamera } from "./type";
import type { InspectResultPayload } from "../../shared/ws";
import "./MainOverview.css";

export function MainOverview() {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<CameraImageUrlsById>({});
  const [inspectResultsByCameraId, setInspectResultsByCameraId] = useState<Record<number, InspectResultPayload>>({});
  const [inspectTriggerStatusByCameraId, setInspectTriggerStatusByCameraId] = useState<Record<number, string>>({});

  const cameraCards = createCameraCards(cameraIds, imageUrlsByCameraId);
  const modalCameraImageUrl = selectedCamera ? imageUrlsByCameraId[selectedCamera.cameraId] : undefined;

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

  useEffect(() => {
    const unsubscribeMessage = orchestratorWs.onMessage((message) => {
      if (message.type !== "server.preview_frame" && message.type !== "server.inspect_result") {
        return;
      }

      const frame = message.payload;
      const imageUrl = createWsFrameImageUrl(frame);

      if (imageUrl) {
        setImageUrlsByCameraId((prevImageUrls) => ({
          ...prevImageUrls,
          [frame.camera_id]: imageUrl,
        }));
      }

      if (message.type === "server.inspect_result") {
        const inspectResult = message.payload;

        setInspectResultsByCameraId((prevInspectResults) => ({
          ...prevInspectResults,
          [inspectResult.camera_id]: inspectResult,
        }));
        setInspectTriggerStatusByCameraId((prevStatuses) => ({
          ...prevStatuses,
          [inspectResult.camera_id]: "Проверка завершена",
        }));
      }
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
    };
  }, []);

  const handleInspectCamera = (cameraId: number) => {
    setInspectTriggerStatusByCameraId((prevStatuses) => ({
      ...prevStatuses,
      [cameraId]: "Проверка поставлена в очередь...",
    }));

    orchestratorApi
      .triggerInspection(cameraId)
      .then(() => {
        setInspectTriggerStatusByCameraId((prevStatuses) => ({
          ...prevStatuses,
          [cameraId]: "Ожидание результата проверки...",
        }));
      })
      .catch((error) => {
        setInspectTriggerStatusByCameraId((prevStatuses) => ({
          ...prevStatuses,
          [cameraId]: error instanceof Error ? error.message : String(error),
        }));
      });
  };

  return (
    <section
      className="camera-overview"
      aria-label="Camera frames"
    >
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
            onOpen={() => setSelectedCamera(createSelectedCamera(camera))}
          />
        ))}
      </div>

      {selectedCamera && (
        <ModalWrapper
          isOpen
          cameraId={selectedCamera.cameraId}
          cameraImageUrl={modalCameraImageUrl}
          headerActions={
            <button
              className="modal__action"
              type="button"
              onClick={() => setStreamCamera(selectedCamera)}
            >
              Открыть стрим
            </button>
          }
          inspectTriggerStatus={inspectTriggerStatusByCameraId[selectedCamera.cameraId]}
          inspectResult={inspectResultsByCameraId[selectedCamera.cameraId]}
          onInspect={() => handleInspectCamera(selectedCamera.cameraId)}
          title={`${selectedCamera.objectName} / Camera ${selectedCamera.cameraId}`}
          onClose={() => setSelectedCamera(null)}
        />
      )}

      {streamCamera && (
        <ServerStream
          isOpen
          cameraId={streamCamera.cameraId}
          title={`${streamCamera.objectName} / Camera ${streamCamera.cameraId}`}
          onClose={() => setStreamCamera(null)}
        />
      )}
    </section>
  );
}
