import { useCallback, useEffect, useRef, useState } from "react";
import { ModalWrapper } from "../ModalWrapper";
import { ServerStream } from "../ServerStream";
import { orchestratorApi } from "../../shared/api";
import type { UiLatestSnapshot } from "../../shared/api/types";
import { resolveInspectionResultState } from "../../shared/inspectResult";
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
import type { HeatmapDescriptor, InspectResultPayload } from "../../shared/ws";
import "./MainOverview.css";

const CAMERAS_PER_OVERVIEW = 5;
const INSPECTION_HISTORY_LIMIT = 20;

type MainOverviewProps = {
  selectedSettingsCameraId: number | null;
  onSettingsCameraToggle: (cameraId: number) => void;
};

type InspectionControlState = {
  isEnabled: boolean;
  state: "idle" | "starting" | "stopping" | "error";
  message: string;
};

type InspectionHistoryItem = {
  frameId: string;
  result: "pass" | "fail";
};

export function MainOverview({ selectedSettingsCameraId, onSettingsCameraToggle }: MainOverviewProps) {
  const [backendStatus, setBackendStatus] = useState<BackendStatus>(INITIAL_BACKEND_STATUS);
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [selectedCamera, setSelectedCamera] = useState<SelectedCamera | null>(null);
  const [streamCamera, setStreamCamera] = useState<SelectedCamera | null>(null);
  const [previewImageUrlsByCameraId, setPreviewImageUrlsByCameraId] = useState<CameraImageUrlsById>({});
  const [previewFrameIdsByCameraId, setPreviewFrameIdsByCameraId] = useState<Record<number, string>>({});
  const [inspectResultsByCameraId, setInspectResultsByCameraId] = useState<Record<number, InspectResultPayload>>({});
  const [inspectArtifactResultsByCameraId, setInspectArtifactResultsByCameraId] = useState<
    Record<number, InspectResultPayload>
  >({});
  const [inspectionHistoryByCameraId, setInspectionHistoryByCameraId] = useState<
    Record<number, InspectionHistoryItem[]>
  >({});
  const [inspectionControlByCameraId, setInspectionControlByCameraId] = useState<
    Record<number, InspectionControlState>
  >({});
  const [hasReference, setHasReference] = useState(false);
  const latestPreviewTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const latestPreviewFrameIdByCameraIdRef = useRef<Record<number, string>>({});
  const latestInspectResultByCameraIdRef = useRef<Record<number, InspectResultPayload>>({});
  const latestArtifactResultByCameraIdRef = useRef<Record<number, InspectResultPayload>>({});
  const pendingPreviewUrlsByCameraIdRef = useRef<CameraImageUrlsById>({});
  const previewUpdateFrameRef = useRef<number | null>(null);

  const cameraCards = createCameraCards(cameraIds, previewImageUrlsByCameraId);
  const cameraCardGroups = Array.from(
    { length: Math.ceil(cameraCards.length / CAMERAS_PER_OVERVIEW) },
    (_, groupIndex) => {
      const startIndex = groupIndex * CAMERAS_PER_OVERVIEW;
      return cameraCards.slice(startIndex, startIndex + CAMERAS_PER_OVERVIEW);
    },
  );
  const modalInspectionControlState = selectedCamera ? inspectionControlByCameraId[selectedCamera.cameraId] : undefined;
  const selectedInspectResult = selectedCamera ? inspectResultsByCameraId[selectedCamera.cameraId] : undefined;
  const selectedArtifactResult = selectedCamera
    ? inspectArtifactResultsByCameraId[selectedCamera.cameraId]
    : undefined;
  const selectedModalInspectResult = resolveDisplayedInspectResult(selectedInspectResult, selectedArtifactResult);
  const displayedInspectImageUrl = selectedModalInspectResult ? createWsFrameImageUrl(selectedModalInspectResult) : undefined;
  const matchingPreviewImageUrl =
    selectedCamera &&
    selectedModalInspectResult &&
    previewFrameIdsByCameraId[selectedCamera.cameraId] === selectedModalInspectResult.frame_id
      ? previewImageUrlsByCameraId[selectedCamera.cameraId]
      : undefined;
  const displayedModalImageUrl = displayedInspectImageUrl ?? matchingPreviewImageUrl;
  const displayedModalHeatmapUrl =
    selectedModalInspectResult?.heatmap ? resolveHeatmapSourceUrlOrUndefined(selectedModalInspectResult.heatmap) : undefined;

  const resetCameraInspectionOrdering = useCallback((cameraId: number) => {
    delete latestInspectResultByCameraIdRef.current[cameraId];
    delete latestArtifactResultByCameraIdRef.current[cameraId];
    setInspectResultsByCameraId((previousResults) => removeCameraResult(previousResults, cameraId));
    setInspectArtifactResultsByCameraId((previousResults) => removeCameraResult(previousResults, cameraId));
  }, []);

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
      void hydrateCardsFromLatestSnapshots(overviewData.cameraIds, () => isActive, {
        setPreviewImageUrlsByCameraId,
        setPreviewFrameIdsByCameraId,
        setInspectResultsByCameraId,
        setInspectArtifactResultsByCameraId,
        setInspectionHistoryByCameraId,
        latestPreviewFrameIdByCameraIdRef,
        latestInspectResultByCameraIdRef,
        latestArtifactResultByCameraIdRef,
      });
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
      if (message.type === "server.hello" || message.type === "server.state") {
        setHasReference(message.payload.session_state !== "NO_REFERENCE");
        return;
      }

      if (message.type === "server.reference_bundle_ack" && message.payload.ok) {
        setHasReference(true);
        return;
      }

      if (message.type === "server.preview_frame") {
        const previewFrame = message.payload;
        const cameraId = previewFrame.camera_id;
        const previousTimestamp = latestPreviewTimestampByCameraIdRef.current[cameraId] ?? 0;

        if (previewFrame.server_ts_ms < previousTimestamp) {
          return;
        }

        const previousFrameId = latestPreviewFrameIdByCameraIdRef.current[cameraId];
        if (previousFrameId && compareFrameIds(previewFrame.frame_id, previousFrameId) < 0) {
          resetCameraInspectionOrdering(cameraId);
        }
        latestPreviewFrameIdByCameraIdRef.current[cameraId] = previewFrame.frame_id;

        const imageUrl = createWsFrameImageUrl(previewFrame);
        if (!imageUrl) {
          return;
        }

        latestPreviewTimestampByCameraIdRef.current[cameraId] = previewFrame.server_ts_ms;
        setPreviewFrameIdsByCameraId((previousFrameIds) => ({
          ...previousFrameIds,
          [cameraId]: previewFrame.frame_id,
        }));
        pendingPreviewUrlsByCameraIdRef.current[cameraId] = imageUrl;

        if (previewUpdateFrameRef.current === null) {
          previewUpdateFrameRef.current = window.requestAnimationFrame(() => {
            previewUpdateFrameRef.current = null;
            const pendingPreviewUrls = pendingPreviewUrlsByCameraIdRef.current;
            pendingPreviewUrlsByCameraIdRef.current = {};
            setPreviewImageUrlsByCameraId((previousImageUrls) => ({
              ...previousImageUrls,
              ...pendingPreviewUrls,
            }));
          });
        }
        return;
      }

      if (message.type !== "server.inspect_result") {
        return;
      }

      const inspectResult = message.payload;
      setHasReference(true);
      const cameraId = inspectResult.camera_id;
      const hasArtifacts = hasDisplayableInspectImage(inspectResult);

      if (hasArtifacts) {
        const currentLiveResult = latestInspectResultByCameraIdRef.current[cameraId];
        const previousArtifactResult = latestArtifactResultByCameraIdRef.current[cameraId];
        if (previousArtifactResult && compareInspectResults(inspectResult, previousArtifactResult) <= 0) {
          return;
        }
        latestArtifactResultByCameraIdRef.current[cameraId] = inspectResult;
        addInspectionHistoryItem(setInspectionHistoryByCameraId, inspectResult);
        setInspectArtifactResultsByCameraId((previousResults) => {
          return {
            ...previousResults,
            [cameraId]: inspectResult,
          };
        });
        if (!currentLiveResult || compareInspectResults(inspectResult, currentLiveResult) >= 0) {
          latestInspectResultByCameraIdRef.current[cameraId] = inspectResult;
          setInspectResultsByCameraId((previousResults) => ({
            ...previousResults,
            [cameraId]: inspectResult,
          }));
        }
        return;
      }

      const previousResult = latestInspectResultByCameraIdRef.current[cameraId];
      if (previousResult && isInspectionCounterReset(previousResult, inspectResult)) {
        resetCameraInspectionOrdering(cameraId);
      }

      const currentResult = latestInspectResultByCameraIdRef.current[cameraId];
      if (currentResult && compareInspectResults(inspectResult, currentResult) < 0) {
        return;
      }
      latestInspectResultByCameraIdRef.current[cameraId] = inspectResult;
      addInspectionHistoryItem(setInspectionHistoryByCameraId, inspectResult);
      setInspectResultsByCameraId((previousResults) => ({
        ...previousResults,
        [cameraId]: inspectResult,
      }));
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
      if (previewUpdateFrameRef.current !== null) {
        window.cancelAnimationFrame(previewUpdateFrameRef.current);
        previewUpdateFrameRef.current = null;
      }
      pendingPreviewUrlsByCameraIdRef.current = {};
    };
  }, [resetCameraInspectionOrdering]);

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
              const artifactInspectResult = inspectArtifactResultsByCameraId[camera.cameraId];
              const inspectImageUrl = resolveCardInspectImageUrl(inspectResult, artifactInspectResult);
              const isInspectionEnabled = inspectionControlState?.isEnabled ?? true;
              const isInspectionActionPending =
                inspectionControlState?.state === "starting" || inspectionControlState?.state === "stopping";

              return (
                <StatusCard
                  key={camera.cameraId}
                  cameraId={camera.cameraId}
                  objectName={camera.objectName}
                  imageUrl={hasReference ? inspectImageUrl : camera.imageUrl}
                  currentFrameId={previewFrameIdsByCameraId[camera.cameraId]}
                  inspectionFrameId={inspectResult?.frame_id}
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

          <div className="inspection-history-grid">
            {cameraGroup.map((camera) => (
              <section
                className="inspection-history"
                aria-label={`Inspection history for camera ${camera.cameraId}`}
                key={camera.cameraId}
              >
                <header>Camera {camera.cameraId}: latest inspections</header>
                <div className="inspection-history__list">
                  {(inspectionHistoryByCameraId[camera.cameraId] ?? []).map((item) => (
                    <div
                      className="inspection-history__item"
                      data-result={item.result}
                      key={item.frameId}
                    >
                      <span>Frame {item.frameId}</span>
                      <strong>{item.result === "pass" ? "Годен" : "Брак"}</strong>
                    </div>
                  ))}
                  {!inspectionHistoryByCameraId[camera.cameraId]?.length && (
                    <div className="inspection-history__empty">Нет результатов</div>
                  )}
                </div>
              </section>
            ))}
          </div>
        </section>
      ))}

      {selectedCamera && (
        <ModalWrapper
          isOpen
          cameraId={selectedCamera.cameraId}
          cameraImageUrl={displayedModalImageUrl}
          inspectHeatmapUrl={displayedModalHeatmapUrl}
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
          inspectResult={selectedModalInspectResult}
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

function resolveCardInspectImageUrl(
  inspectResult: InspectResultPayload | undefined,
  artifactInspectResult: InspectResultPayload | undefined,
) {
  if (
    !inspectResult ||
    !artifactInspectResult?.artifact_bundle_id ||
    artifactInspectResult.frame_id !== inspectResult.frame_id
  ) {
    return undefined;
  }

  return orchestratorApi.url(
    `/api/inspection-artifacts/${encodeURIComponent(artifactInspectResult.artifact_bundle_id)}/card.jpg`,
  );
}

function resolveDisplayedInspectResult(
  inspectResult: InspectResultPayload | undefined,
  artifactInspectResult: InspectResultPayload | undefined,
) {
  return artifactInspectResult ?? inspectResult;
}

function removeCameraResult(
  results: Record<number, InspectResultPayload>,
  cameraId: number,
) {
  if (!(cameraId in results)) {
    return results;
  }

  const nextResults = { ...results };
  delete nextResults[cameraId];
  return nextResults;
}

function compareInspectResults(left: InspectResultPayload, right: InspectResultPayload) {
  const frameOrder = compareFrameIds(left.frame_id, right.frame_id);
  return frameOrder !== 0 ? frameOrder : Math.sign(left.server_ts_ms - right.server_ts_ms);
}

function compareFrameIds(left: string, right: string) {
  try {
    const leftId = BigInt(left);
    const rightId = BigInt(right);
    return leftId === rightId ? 0 : leftId > rightId ? 1 : -1;
  } catch {
    return left.localeCompare(right, undefined, { numeric: true });
  }
}

function isInspectionCounterReset(
  previous: InspectResultPayload,
  candidate: InspectResultPayload,
) {
  return (
    candidate.server_ts_ms >= previous.server_ts_ms &&
    compareFrameIds(candidate.frame_id, previous.frame_id) < 0
  );
}

function hasDisplayableInspectImage(inspectResult: InspectResultPayload) {
  const imagePath = inspectResult.http_path ?? inspectResult.current.http_path;
  return Boolean(imagePath);
}

function resolveHeatmapSourceUrlOrUndefined(heatmap: HeatmapDescriptor) {
  if (heatmap.http_path) {
    return orchestratorApi.url(heatmap.http_path);
  }
  if (heatmap.artifact_id) {
    return orchestratorApi.heatmapArtifactUrl(heatmap.artifact_id);
  }
  return undefined;
}

function addInspectionHistoryItem(
  setHistory: React.Dispatch<React.SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  inspectResult: InspectResultPayload,
) {
  const result = resolveInspectionResultState(inspectResult);
  if (!result) {
    return;
  }

  setHistory((current) => {
    const cameraHistory = current[inspectResult.camera_id] ?? [];
    const existing = cameraHistory.find(
      (item) => item.frameId === inspectResult.frame_id && item.result === result,
    );
    if (existing) {
      return current;
    }

    const nextCameraHistory = [
      { frameId: inspectResult.frame_id, result },
      ...cameraHistory.filter((item) => item.frameId !== inspectResult.frame_id),
    ].slice(0, INSPECTION_HISTORY_LIMIT);

    return {
      ...current,
      [inspectResult.camera_id]: nextCameraHistory,
    };
  });
}

async function hydrateCardsFromLatestSnapshots(
  cameraIds: number[],
  isActive: () => boolean,
  deps: {
    setPreviewImageUrlsByCameraId: React.Dispatch<React.SetStateAction<CameraImageUrlsById>>;
    setPreviewFrameIdsByCameraId: React.Dispatch<React.SetStateAction<Record<number, string>>>;
    setInspectResultsByCameraId: React.Dispatch<React.SetStateAction<Record<number, InspectResultPayload>>>;
    setInspectArtifactResultsByCameraId: React.Dispatch<
      React.SetStateAction<Record<number, InspectResultPayload>>
    >;
    setInspectionHistoryByCameraId: React.Dispatch<
      React.SetStateAction<Record<number, InspectionHistoryItem[]>>
    >;
    latestPreviewFrameIdByCameraIdRef: React.MutableRefObject<Record<number, string>>;
    latestInspectResultByCameraIdRef: React.MutableRefObject<Record<number, InspectResultPayload>>;
    latestArtifactResultByCameraIdRef: React.MutableRefObject<Record<number, InspectResultPayload>>;
  },
) {
  const snapshots = await Promise.all(
    cameraIds.map((cameraId) => orchestratorApi.getLatestSnapshot(cameraId).catch(() => null)),
  );
  if (!isActive()) {
    return;
  }

  const previewUrls: CameraImageUrlsById = {};
  const previewFrameIds: Record<number, string> = {};
  const inspectResults: Record<number, InspectResultPayload> = {};
  const artifactResults: Record<number, InspectResultPayload> = {};
  const inspectionHistory: Record<number, InspectionHistoryItem[]> = {};

  for (const snapshot of snapshots) {
    if (!snapshot) {
      continue;
    }
    const frameId = String(snapshot.frameId);
    if (snapshot.hasCurrent && snapshot.currentJpeg?.path && snapshot.frameId >= 0) {
      previewUrls[snapshot.cameraId] = orchestratorApi.imageUrl(snapshot.currentJpeg.path, snapshot.frameId);
      previewFrameIds[snapshot.cameraId] = frameId;
      deps.latestPreviewFrameIdByCameraIdRef.current[snapshot.cameraId] = frameId;
    }

    const inspectResult = latestSnapshotToInspectResult(snapshot);
    if (!inspectResult) {
      continue;
    }
    inspectResults[snapshot.cameraId] = inspectResult;
    deps.latestInspectResultByCameraIdRef.current[snapshot.cameraId] = inspectResult;
    if (hasDisplayableInspectImage(inspectResult)) {
      artifactResults[snapshot.cameraId] = inspectResult;
      deps.latestArtifactResultByCameraIdRef.current[snapshot.cameraId] = inspectResult;
    }
    const resultState = resolveInspectionResultState(inspectResult);
    if (resultState) {
      inspectionHistory[snapshot.cameraId] = [{ frameId: inspectResult.frame_id, result: resultState }];
    }
  }

  if (Object.keys(previewUrls).length > 0) {
    deps.setPreviewImageUrlsByCameraId((current) => ({ ...current, ...previewUrls }));
  }
  if (Object.keys(previewFrameIds).length > 0) {
    deps.setPreviewFrameIdsByCameraId((current) => ({ ...current, ...previewFrameIds }));
  }
  if (Object.keys(inspectResults).length > 0) {
    deps.setInspectResultsByCameraId((current) => ({ ...current, ...inspectResults }));
  }
  if (Object.keys(artifactResults).length > 0) {
    deps.setInspectArtifactResultsByCameraId((current) => ({ ...current, ...artifactResults }));
  }
  if (Object.keys(inspectionHistory).length > 0) {
    deps.setInspectionHistoryByCameraId((current) => ({ ...current, ...inspectionHistory }));
  }
}

function latestSnapshotToInspectResult(snapshot: UiLatestSnapshot): InspectResultPayload | undefined {
  const hasDecision =
    snapshot.python_status != null ||
    snapshot.geometry_status != null ||
    snapshot.overall_pass != null ||
    snapshot.action != null;
  if (!hasDecision || snapshot.frameId < 0) {
    return undefined;
  }

  return {
    camera_id: snapshot.cameraId,
    frame_id: String(snapshot.frameId),
    session_state: "READY",
    current: {
      camera_id: snapshot.cameraId,
      frame_id: String(snapshot.frameId),
      shm_name: snapshot.shmName ?? "",
      width: snapshot.capture.width,
      height: snapshot.capture.height,
      stride: 0,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
      http_path: snapshot.currentJpeg?.path,
    },
    http_path: snapshot.currentJpeg?.path,
    artifact_bundle_id: undefined,
    heatmap:
      snapshot.hasHeatmap && snapshot.heatmapU8?.path
        ? {
            width: snapshot.heatmapU8.width,
            height: snapshot.heatmapU8.height,
            pixel_format: "gray_u8",
            channels: 1,
            http_path: snapshot.heatmapU8.path,
          }
        : null,
    active_reference_view_index: 0,
    detector: {
      detector_id: snapshot.detectorId,
      product_type: snapshot.productType,
    },
    overall_pass: snapshot.overall_pass ?? undefined,
    action: snapshot.action ?? undefined,
    anomaly_score: snapshot.anomaly_score ?? undefined,
    python_status: snapshot.python_status ?? undefined,
    geometry_status: snapshot.geometry_status ?? undefined,
    fp_zones: [],
    server_ts_ms: snapshot.updatedAtMs,
  };
}
