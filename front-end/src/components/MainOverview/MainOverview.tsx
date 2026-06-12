import { useEffect, useRef, useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { ServerStream } from "../ServerStream";
import { orchestratorApi } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
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

const PREVIEW_UPDATE_INTERVAL_MS = 30;
const CAMERAS_PER_OVERVIEW = 5;

type MainOverviewProps = {
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
};

type InspectionControlState = {
  isEnabled: boolean;
  state: "idle" | "starting" | "stopping" | "error";
  message: string;
};

type InspectionFrame = {
  imageUrl: string;
  frameId: string;
};

export function MainOverview({ selectedSettingsCameraId, onSettingsCameraToggle }: MainOverviewProps) {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const [previewImageUrlsByCameraId, setPreviewImageUrlsByCameraId] = useState<CameraImageUrlsById>({});
  const [inspectResultsByCameraId, setInspectResultsByCameraId] = useState<Record<number, InspectResultPayload>>({});
  const [inspectionFramesByCameraId, setInspectionFramesByCameraId] = useState<Record<number, InspectionFrame>>({});
  const [inspectionControlByCameraId, setInspectionControlByCameraId] = useState<
    Record<number, InspectionControlState>
  >({});
  const latestPreviewTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const latestInspectTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const pendingPreviewUrlsByCameraIdRef = useRef<CameraImageUrlsById>({});
  const previewUpdateTimerRef = useRef<number | null>(null);

  const cameraCards = createCameraCards(cameraIds, previewImageUrlsByCameraId);
  const cameraCardGroups = Array.from(
    { length: Math.ceil(cameraCards.length / CAMERAS_PER_OVERVIEW) },
    (_, groupIndex) => {
      const startIndex = groupIndex * CAMERAS_PER_OVERVIEW;
      return cameraCards.slice(startIndex, startIndex + CAMERAS_PER_OVERVIEW);
    },
  );
  const modalCameraPreviewUrl = selectedCamera ? previewImageUrlsByCameraId[selectedCamera.cameraId] : undefined;
  const modalInspectionControlState = selectedCamera ? inspectionControlByCameraId[selectedCamera.cameraId] : undefined;

  const toggleInspection = async (cameraId: number) => {
    const currentControl = inspectionControlByCameraId[cameraId];
    if (currentControl?.state === "starting" || currentControl?.state === "stopping") {
      return;
    }
    const wasEnabled = currentControl?.isEnabled ?? true;
    const nextEnabled = !wasEnabled;

    setInspectionControlByCameraId((currentStates) => ({
      ...currentStates,
      [cameraId]: {
        isEnabled: wasEnabled,
        state: nextEnabled ? "starting" : "stopping",
        message: nextEnabled ? "Starting inspection..." : "Stopping inspection...",
      },
    }));

    try {
      const response = await orchestratorApi.setInspectionEnabled(cameraId, nextEnabled);

      if (response.unknownCameraIds.includes(cameraId)) {
        throw new Error(`Camera ${cameraId} is not configured`);
      }

      const isEnabled = response.enabledCameraIds.includes(cameraId);
      setInspectionControlByCameraId((currentStates) => ({
        ...currentStates,
        [cameraId]: {
          isEnabled,
          state: "idle",
          message: isEnabled ? "Inspection enabled" : "Inspection stopped",
        },
      }));
    } catch (error) {
      setInspectionControlByCameraId((currentStates) => ({
        ...currentStates,
        [cameraId]: {
          isEnabled: wasEnabled,
          state: "error",
          message: errorMessage(error),
        },
      }));
    }
  };

  useEffect(() => {
    let isActive = true;

    Promise.all([
      loadMainOverviewData().catch(createMainOverviewErrorData),
      orchestratorApi.getInspectionStatus().catch(() => null),
    ]).then(([overviewData, inspectionStatus]) => {
      if (!isActive) {
        return;
      }

      setBackendStatus(overviewData.backendStatus);
      setCameraIds(overviewData.cameraIds);
      if (inspectionStatus) {
        const nextControlStates: Record<number, InspectionControlState> = {};
        for (const cameraId of inspectionStatus.enabledCameraIds) {
          nextControlStates[cameraId] = {
            isEnabled: true,
            state: "idle",
            message: "Inspection enabled",
          };
        }
        for (const cameraId of inspectionStatus.disabledCameraIds) {
          nextControlStates[cameraId] = {
            isEnabled: false,
            state: "idle",
            message: "Inspection stopped",
          };
        }
        setInspectionControlByCameraId(nextControlStates);
      }
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
      const inspectionImageUrl = createWsFrameImageUrl(inspectResult);
      if (inspectionImageUrl) {
        setInspectionFramesByCameraId((previousFrames) => ({
          ...previousFrames,
          [cameraId]: {
            imageUrl: inspectionImageUrl,
            frameId: inspectResult.frame_id,
          },
        }));
      }
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
    <div className="camera-overviews">
      {cameraCardGroups.map((cameraGroup, groupIndex) => (
        <section
          className="camera-overview"
          aria-label={`Camera frames for object ${groupIndex + 1}`}
          key={groupIndex}
        >
          <div className="backend-status-row">
            <span>Status</span>
            <strong data-status={backendStatus.state}>{backendStatus.text}</strong>
          </div>

          <div className="camera-grid">
            {cameraGroup.map((camera) => {
              const inspectionControlState = inspectionControlByCameraId[camera.cameraId];
              const inspectResult = inspectResultsByCameraId[camera.cameraId];
              const inspectionFrame = inspectionFramesByCameraId[camera.cameraId];
              const isInspectionEnabled = inspectionControlState?.isEnabled ?? true;
              const isInspectionActionPending =
                inspectionControlState?.state === "starting" || inspectionControlState?.state === "stopping";

              return (
                <StatusCard
                  key={camera.cameraId}
                  cameraId={camera.cameraId}
                  objectName={camera.objectName}
                  imageUrl={camera.imageUrl}
                  inspectionImageUrl={inspectionFrame?.imageUrl}
                  inspectionFrameId={inspectionFrame?.frameId}
                  isSelected={selectedSettingsCameraId === camera.cameraId}
                  isInspectionEnabled={isInspectionEnabled}
                  isInspectionActionDisabled={isInspectionActionPending}
                  inspectionActionLabel={
                    inspectionControlState?.state === "starting"
                      ? "Starting..."
                      : inspectionControlState?.state === "stopping"
                        ? "Stopping..."
                        : isInspectionEnabled
                          ? "Stop"
                          : "Start"
                  }
                  inspectionStatus={inspectionControlState?.message}
                  inspectionResult={resolveInspectionResultState(inspectResult)}
                  onOpen={() => setSelectedCamera(createSelectedCamera(camera))}
                  onSelect={() => onSettingsCameraToggle(camera.cameraId)}
                  onInspectionToggle={() => void toggleInspection(camera.cameraId)}
                />
              );
            })}
          </div>
        </section>
      ))}

      {selectedCamera && (
        <ModalWrapper
          isOpen
          cameraId={selectedCamera.cameraId}
          cameraImageUrl={modalCameraPreviewUrl}
          dangerHeaderAction={
            <button
              className={
                modalInspectionControlState?.isEnabled === false
                  ? "modal__action"
                  : "modal__action modal__action--danger"
              }
              type="button"
              disabled={
                modalInspectionControlState?.state === "starting" || modalInspectionControlState?.state === "stopping"
              }
              title={modalInspectionControlState?.message}
              onClick={() => void toggleInspection(selectedCamera.cameraId)}
            >
              {modalInspectionControlState?.state === "starting"
                ? "Starting..."
                : modalInspectionControlState?.state === "stopping"
                  ? "Stopping..."
                  : modalInspectionControlState?.isEnabled === false
                    ? "Start inspection"
                    : "Stop inspection"}
            </button>
          }
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
    </div>
  );
}

function resolveInspectionResultState(inspectResult?: InspectResultPayload): "pass" | "fail" | undefined {
  if (typeof inspectResult?.overall_pass === "boolean") {
    return inspectResult.overall_pass ? "pass" : "fail";
  }

  const action = inspectResult?.action?.toUpperCase();
  if (action === "ACCEPT") {
    return "pass";
  }
  if (action === "REJECT") {
    return "fail";
  }

  return undefined;
}
