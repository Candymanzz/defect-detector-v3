import { useEffect, useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { ServerStream } from "../ServerStream";
import { orchestratorWs } from "../../shared/ws";
import { StatusCard } from "../../shared/ui/StatusCard";
import {
  createMainOverviewErrorData,
  createCameraCards,
  createSelectedCamera,
  createWsFrameImageUrl,
  FALLBACK_CAMERA_IDS,
  INITIAL_BACKEND_STATUS,
  loadLatestCameraImageUrl,
  loadMainOverviewData,
} from "./MainController";
import type { BackendStatus, CameraImageUrlsById, SelectedCamera } from "./type";
import type { InspectResultPayload } from "../../shared/ws";
import "./MainOverview.css";

type MainOverviewProps = {
  selectedCameraId: number | null;
  onSelectedCameraIdChange: (cameraId: number | null) => void;
};

export function MainOverview({ selectedCameraId, onSelectedCameraIdChange }: MainOverviewProps) {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<CameraImageUrlsById>({});
  const [inspectResultsByCameraId, setInspectResultsByCameraId] = useState<Record<number, InspectResultPayload>>({});

  const backendReady = backendStatus.state === "ready";
  const cameraCards = createCameraCards(cameraIds, backendReady, imageUrlsByCameraId);
  const modalCameraImageUrl = selectedCamera && backendReady ? imageUrlsByCameraId[selectedCamera.cameraId] : undefined;

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
    if (!backendReady) {
      return;
    }

    orchestratorWs.connect();

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
      }
    });

    return () => {
      unsubscribeMessage();
    };
  }, [backendReady]);

  useEffect(() => {
    if (!backendReady || !selectedCamera || imageUrlsByCameraId[selectedCamera.cameraId]) {
      return;
    }

    let isActive = true;

    loadLatestCameraImageUrl(selectedCamera.cameraId)
      .then((imageUrl) => {
        if (!isActive || !imageUrl) {
          return;
        }

        setImageUrlsByCameraId((prevImageUrls) => ({
          ...prevImageUrls,
          [selectedCamera.cameraId]: imageUrl,
        }));
      })
      .catch(() => {
        // Latest snapshot is a convenience fallback; WS frames remain the primary source.
      });

    return () => {
      isActive = false;
    };
  }, [backendReady, imageUrlsByCameraId, selectedCamera]);

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
            selected={selectedCameraId === camera.cameraId}
            onSelect={() => onSelectedCameraIdChange(selectedCameraId === camera.cameraId ? null : camera.cameraId)}
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
          inspectResult={inspectResultsByCameraId[selectedCamera.cameraId]}
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
