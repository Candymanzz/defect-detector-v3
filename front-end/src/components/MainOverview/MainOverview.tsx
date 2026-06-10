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
  loadMainOverviewData,
} from "./MainController";
import type { BackendStatus, CameraImageUrlsById, SelectedCamera } from "./type";
import type { InspectResultPayload } from "../../shared/ws";
import "./MainOverview.css";

type MainOverviewProps = {
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
};

export function MainOverview({ selectedSettingsCameraId, onSettingsCameraToggle }: MainOverviewProps) {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const [previewImageUrlsByCameraId, setPreviewImageUrlsByCameraId] = useState<CameraImageUrlsById>({});
  const [inspectResultsByCameraId, setInspectResultsByCameraId] = useState<Record<number, InspectResultPayload>>({});

  const cameraCards = createCameraCards(cameraIds, previewImageUrlsByCameraId);

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
      if (message.type === "server.preview_frame") {
        const previewFrame = message.payload;
        const imageUrl = createWsFrameImageUrl(previewFrame);

        if (imageUrl) {
          setPreviewImageUrlsByCameraId((previousImageUrls) => ({
            ...previousImageUrls,
            [previewFrame.camera_id]: imageUrl,
          }));
        }

        return;
      }

      if (message.type === "server.inspect_result") {
        const inspectResult = message.payload;

        setInspectResultsByCameraId((prevInspectResults) => ({
          ...prevInspectResults,
          [inspectResult.camera_id]: inspectResult,
        }));
      }
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
    };
  }, []);

  return (
    <section
      className="camera-overview"
      aria-label="Camera frames"
    >
      <div className="backend-status-row">
        <span>Status</span>
        <strong data-status={backendStatus.state}>{backendStatus.text}</strong>
      </div>

      <div className="camera-grid">
        {cameraCards.map((camera) => (
          <StatusCard
            key={camera.cameraId}
            cameraId={camera.cameraId}
            objectName={camera.objectName}
            imageUrl={camera.imageUrl}
            isSelected={selectedSettingsCameraId === camera.cameraId}
            onOpen={() => setSelectedCamera(createSelectedCamera(camera))}
            onSelect={() => onSettingsCameraToggle(camera.cameraId)}
          />
        ))}
      </div>

      {selectedCamera && (
        <ModalWrapper
          isOpen
          cameraId={selectedCamera.cameraId}
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
