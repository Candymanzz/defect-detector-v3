import { useEffect, useRef, useState } from "react";
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

const PREVIEW_UPDATE_INTERVAL_MS = 100;

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
  const latestPreviewTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const latestInspectTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const pendingPreviewUrlsByCameraIdRef = useRef<CameraImageUrlsById>({});
  const previewUpdateTimerRef = useRef<number | null>(null);

  const cameraCards = createCameraCards(cameraIds, previewImageUrlsByCameraId);
  const modalCameraPreviewUrl = selectedCamera ? previewImageUrlsByCameraId[selectedCamera.cameraId] : undefined;

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
        const cameraId = previewFrame.camera_id;
        const previousTimestamp = latestPreviewTimestampByCameraIdRef.current[cameraId] ?? 0;

        if (previewFrame.server_ts_ms < previousTimestamp) {
          return;
        }

        const imageUrl = createWsFrameImageUrl(previewFrame);
        if (!imageUrl) {
          return;
        }

        latestPreviewTimestampByCameraIdRef.current[cameraId] = previewFrame.server_ts_ms;
        pendingPreviewUrlsByCameraIdRef.current[cameraId] = imageUrl;

        if (previewUpdateTimerRef.current === null) {
          previewUpdateTimerRef.current = window.setTimeout(() => {
            previewUpdateTimerRef.current = null;
            const pendingPreviewUrls = pendingPreviewUrlsByCameraIdRef.current;
            pendingPreviewUrlsByCameraIdRef.current = {};
            setPreviewImageUrlsByCameraId((previousImageUrls) => ({
              ...previousImageUrls,
              ...pendingPreviewUrls,
            }));
          }, PREVIEW_UPDATE_INTERVAL_MS);
        }
        return;
      }

      if (message.type !== "server.inspect_result") {
        return;
      }

      const inspectResult = message.payload;
      const cameraId = inspectResult.camera_id;
      const previousTimestamp = latestInspectTimestampByCameraIdRef.current[cameraId] ?? 0;

      if (inspectResult.server_ts_ms < previousTimestamp) {
        return;
      }

      latestInspectTimestampByCameraIdRef.current[cameraId] = inspectResult.server_ts_ms;
      setInspectResultsByCameraId((prevInspectResults) => ({
        ...prevInspectResults,
        [cameraId]: inspectResult,
      }));
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
      if (previewUpdateTimerRef.current !== null) {
        window.clearTimeout(previewUpdateTimerRef.current);
        previewUpdateTimerRef.current = null;
      }
      pendingPreviewUrlsByCameraIdRef.current = {};
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
          cameraImageUrl={modalCameraPreviewUrl}
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
