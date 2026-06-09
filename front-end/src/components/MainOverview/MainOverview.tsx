import { useEffect, useRef, useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { ServerStream } from "../ServerStream";
import { orchestratorWs } from "../../shared/ws";
import { Button } from "../../shared/ui/Button";
import { StatusCard } from "../../shared/ui/StatusCard";
import {
  createBackendErrorStatus,
  createCameraCards,
  createSelectedCamera,
  createWsFrameImageUrl,
  FALLBACK_CAMERA_IDS,
  INITIAL_BACKEND_STATUS,
  isFrameSequenceReset,
  isFramePayloadConsistent,
  isIncomingFrameNewer,
  loadBackendCameraIds,
  loadBackendStatus,
  readNumericFrameId,
} from "./MainController";
import { OverviewStat } from "./OverviewStat";
import { createOverviewStats } from "./overviewStats";
import type { BackendStatus, CameraImageUrlsById, SelectedCamera } from "./type";
import type { InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import "./MainOverview.css";

const BACKEND_HEALTH_POLL_MS = 5000;
const CAMERA_LIST_POLL_MS = 10000;

type FrameGenerationGuard = {
  currentMaxFrameId: bigint;
  previousMaxFrameId: bigint;
};

type MainOverviewProps = {
  isPreviewPaused?: boolean;
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
  onBackendStatusChange?: (status: BackendStatus) => void;
};

export function MainOverview({
  isPreviewPaused = false,
  selectedSettingsCameraId,
  onSettingsCameraToggle,
  onBackendStatusChange,
}: MainOverviewProps) {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<CameraImageUrlsById>({});
  const [inspectResultsByCameraId, setInspectResultsByCameraId] = useState<Record<number, InspectResultPayload>>({});
  const backendRequestInFlightRef = useRef(false);
  const cameraListRequestInFlightRef = useRef(false);
  const isPreviewPausedRef = useRef(isPreviewPaused);
  const latestVisualFramesRef = useRef<Record<number, PreviewFramePayload | InspectResultPayload>>({});
  const latestPreviewFramesRef = useRef<Record<number, PreviewFramePayload>>({});
  const latestInspectResultsRef = useRef<Record<number, InspectResultPayload>>({});
  const frameGenerationGuardsRef = useRef<Record<number, FrameGenerationGuard>>({});

  const cameraCards = createCameraCards(cameraIds, imageUrlsByCameraId);
  const modalCameraImageUrl = selectedCamera
    ? cameraCards.find((camera) => camera.cameraId === selectedCamera.cameraId)?.imageUrl
    : undefined;
  const onlineCameraCount = cameraCards.filter((camera) => camera.signalState === "online").length;
  const offlineCameraCount = cameraCards.filter((camera) => camera.signalState === "offline").length;
  const waitingCameraCount = cameraCards.filter((camera) => camera.signalState === "waiting").length;
  const lastInspectResult = Object.values(inspectResultsByCameraId).reduce<InspectResultPayload | undefined>(
    (latest, result) => (!latest || result.server_ts_ms > latest.server_ts_ms ? result : latest),
    undefined,
  );
  const overviewStats = createOverviewStats({
    backendStatus,
    cameraCount: cameraCards.length,
    offlineCameraCount,
    lastInspectResult,
    onlineCameraCount,
    waitingCameraCount,
  });

  useEffect(() => {
    let isActive = true;

    const refreshBackendStatus = () => {
      if (backendRequestInFlightRef.current) {
        return;
      }

      backendRequestInFlightRef.current = true;
      loadBackendStatus()
        .catch(createBackendErrorStatus)
        .then((nextBackendStatus) => {
          if (!isActive) {
            return;
          }

          setBackendStatus(nextBackendStatus);
          onBackendStatusChange?.(nextBackendStatus);
        })
        .finally(() => {
          backendRequestInFlightRef.current = false;
        });
    };

    refreshBackendStatus();
    const intervalId = window.setInterval(refreshBackendStatus, BACKEND_HEALTH_POLL_MS);

    return () => {
      isActive = false;
      window.clearInterval(intervalId);
    };
  }, [onBackendStatusChange]);

  useEffect(() => {
    let isActive = true;

    const refreshCameraIds = () => {
      if (cameraListRequestInFlightRef.current) {
        return;
      }

      cameraListRequestInFlightRef.current = true;
      loadBackendCameraIds()
        .then((nextCameraIds) => {
          if (!isActive) {
            return;
          }

          setCameraIds(nextCameraIds);
        })
        .catch(() => {
          // Keep the last known camera IDs when the endpoint is unavailable.
        })
        .finally(() => {
          cameraListRequestInFlightRef.current = false;
        });
    };

    refreshCameraIds();
    const intervalId = window.setInterval(refreshCameraIds, CAMERA_LIST_POLL_MS);

    return () => {
      isActive = false;
      window.clearInterval(intervalId);
    };
  }, []);

  useEffect(() => {
    isPreviewPausedRef.current = isPreviewPaused;
  }, [isPreviewPaused]);

  useEffect(() => {
    let wasOpen = false;
    const unsubscribeStatus = orchestratorWs.onStatus((status) => {
      if (status.state === "open" && !wasOpen) {
        latestVisualFramesRef.current = {};
        latestPreviewFramesRef.current = {};
        latestInspectResultsRef.current = {};
        frameGenerationGuardsRef.current = {};
      }
      wasOpen = status.state === "open";
    });
    const unsubscribeMessage = orchestratorWs.onMessage((message) => {
      if (message.type !== "server.preview_frame" && message.type !== "server.inspect_result") {
        return;
      }

      if (isPreviewPausedRef.current) {
        return;
      }

      const frame = message.payload;
      if (!isFramePayloadConsistent(frame)) {
        return;
      }

      const currentVisualFrame = latestVisualFramesRef.current[frame.camera_id];
      const currentPreviewFrame = latestPreviewFramesRef.current[frame.camera_id];
      const sequenceReset =
        message.type === "server.preview_frame" && isFrameSequenceReset(message.payload, currentPreviewFrame);

      if (sequenceReset) {
        const previousFrameIds = [
          readNumericFrameId(currentVisualFrame),
          readNumericFrameId(currentPreviewFrame),
          readNumericFrameId(latestInspectResultsRef.current[frame.camera_id]),
        ].filter((frameId): frameId is bigint => frameId !== null);
        const incomingFrameId = readNumericFrameId(message.payload);
        const previousMaxFrameId = previousFrameIds.reduce(
          (maxFrameId, frameId) => (frameId > maxFrameId ? frameId : maxFrameId),
          0n,
        );

        if (incomingFrameId !== null && previousMaxFrameId > incomingFrameId) {
          frameGenerationGuardsRef.current[frame.camera_id] = {
            currentMaxFrameId: incomingFrameId,
            previousMaxFrameId,
          };
        }
        delete latestVisualFramesRef.current[frame.camera_id];
        delete latestInspectResultsRef.current[frame.camera_id];
        setInspectResultsByCameraId((prevInspectResults) => {
          const nextInspectResults = { ...prevInspectResults };
          delete nextInspectResults[frame.camera_id];
          return nextInspectResults;
        });
      }

      const belongsToCurrentGeneration =
        message.type === "server.preview_frame" ||
        belongsToCurrentFrameGeneration(frameGenerationGuardsRef.current[frame.camera_id], message.payload);

      if (belongsToCurrentGeneration && (sequenceReset || isIncomingFrameNewer(frame, currentVisualFrame))) {
        latestVisualFramesRef.current[frame.camera_id] = frame;
        const imageUrl = createWsFrameImageUrl(frame);

        setImageUrlsByCameraId((prevImageUrls) => {
          const nextImageUrls = { ...prevImageUrls };
          if (imageUrl) {
            nextImageUrls[frame.camera_id] = imageUrl;
          } else {
            delete nextImageUrls[frame.camera_id];
          }
          return nextImageUrls;
        });
      }

      if (message.type === "server.preview_frame") {
        latestPreviewFramesRef.current[frame.camera_id] = message.payload;
        updateFrameGenerationGuard(frameGenerationGuardsRef.current, message.payload);
      }

      if (message.type === "server.inspect_result") {
        const inspectResult = message.payload;
        const currentInspectResult = latestInspectResultsRef.current[inspectResult.camera_id];

        if (belongsToCurrentGeneration && isIncomingFrameNewer(inspectResult, currentInspectResult)) {
          latestInspectResultsRef.current[inspectResult.camera_id] = inspectResult;
          setInspectResultsByCameraId((prevInspectResults) => ({
            ...prevInspectResults,
            [inspectResult.camera_id]: inspectResult,
          }));
        }
      }

    });

    orchestratorWs.connect();

    return () => {
      unsubscribeStatus();
      unsubscribeMessage();
    };
  }, []);

  return (
    <section
      className="camera-overview"
      aria-label="Camera frames"
    >
      <div className="overview-stats">
        {overviewStats.map((item) => (
          <OverviewStat
            key={item.id}
            item={item}
          />
        ))}
      </div>

      <div className="camera-panel">
        <div className="camera-panel__header">
          <h2>Камеры</h2>
        </div>

        <div className="camera-grid">
          {cameraCards.map((camera) => (
            <StatusCard
              key={camera.cameraId}
              cameraId={camera.cameraId}
              objectName={camera.objectName}
              imageUrl={camera.imageUrl}
              isSelected={selectedSettingsCameraId === camera.cameraId}
              signalState={camera.signalState}
              onOpen={() => setSelectedCamera(createSelectedCamera(camera))}
              onSelect={() => onSettingsCameraToggle(camera.cameraId)}
            />
          ))}
        </div>
      </div>

      {selectedCamera && !streamCamera && (
        <ModalWrapper
          isOpen
          cameraId={selectedCamera.cameraId}
          cameraImageUrl={modalCameraImageUrl}
          headerActions={
            <Button
              type="button"
              variant="ghost"
              onClick={() => setStreamCamera(selectedCamera)}
            >
              Открыть стрим
            </Button>
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

function updateFrameGenerationGuard(
  guards: Record<number, FrameGenerationGuard>,
  previewFrame: PreviewFramePayload,
) {
  const guard = guards[previewFrame.camera_id];
  const frameId = readNumericFrameId(previewFrame);
  if (!guard || frameId === null) {
    return;
  }

  guard.currentMaxFrameId = frameId > guard.currentMaxFrameId ? frameId : guard.currentMaxFrameId;
  if (guard.currentMaxFrameId >= guard.previousMaxFrameId) {
    delete guards[previewFrame.camera_id];
  }
}

function belongsToCurrentFrameGeneration(
  guard: FrameGenerationGuard | undefined,
  inspectResult: InspectResultPayload,
) {
  const frameId = readNumericFrameId(inspectResult);
  if (!guard || frameId === null) {
    return true;
  }

  const generationBoundary = (guard.currentMaxFrameId + guard.previousMaxFrameId) / 2n;
  return frameId <= generationBoundary;
}
