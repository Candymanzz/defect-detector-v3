import { useCallback, useEffect, useRef, useState } from "react";
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
import type { HeatmapDescriptor, InspectResultPayload } from "../../shared/ws";
import "./MainOverview.css";

const PREVIEW_UPDATE_INTERVAL_MS = 30;
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

type InspectSnapshot = {
  inspectResult: InspectResultPayload;
  imageUrl: string;
  heatmapUrl: string;
};

type InspectSnapshotLoadState = {
  state: "idle" | "loading" | "error";
  message?: string;
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
  const [modalInspectSnapshot, setModalInspectSnapshot] = useState<InspectSnapshot>();
  const [modalInspectSnapshotLoadState, setModalInspectSnapshotLoadState] = useState<InspectSnapshotLoadState>({
    state: "idle",
  });
  const [inspectionControlByCameraId, setInspectionControlByCameraId] = useState<
    Record<number, InspectionControlState>
  >({});
  const latestPreviewTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const latestPreviewFrameIdByCameraIdRef = useRef<Record<number, string>>({});
  const latestInspectResultByCameraIdRef = useRef<Record<number, InspectResultPayload>>({});
  const latestArtifactResultByCameraIdRef = useRef<Record<number, InspectResultPayload>>({});
  const awaitingLiveResultAfterResetByCameraIdRef = useRef<Record<number, boolean>>({});
  const pendingPreviewUrlsByCameraIdRef = useRef<CameraImageUrlsById>({});
  const previewUpdateTimerRef = useRef<number | null>(null);
  const modalInspectSnapshotRef = useRef<InspectSnapshot | undefined>(undefined);
  const retiredInspectSnapshotsRef = useRef<InspectSnapshot[]>([]);
  const selectedModalCameraIdRef = useRef<number | undefined>(undefined);
  const pendingInspectResultRef = useRef<InspectResultPayload | undefined>(undefined);
  const activeInspectResultRef = useRef<InspectResultPayload | undefined>(undefined);
  const inspectSnapshotLoadControllerRef = useRef<AbortController | undefined>(undefined);

  const cameraCards = createCameraCards(cameraIds, previewImageUrlsByCameraId);
  const cameraCardGroups = Array.from(
    { length: Math.ceil(cameraCards.length / CAMERAS_PER_OVERVIEW) },
    (_, groupIndex) => {
      const startIndex = groupIndex * CAMERAS_PER_OVERVIEW;
      return cameraCards.slice(startIndex, startIndex + CAMERAS_PER_OVERVIEW);
    },
  );
  const modalInspectionControlState = selectedCamera ? inspectionControlByCameraId[selectedCamera.cameraId] : undefined;
  const displayedModalInspectSnapshot =
    selectedCamera && modalInspectSnapshot?.inspectResult.camera_id === selectedCamera.cameraId
      ? modalInspectSnapshot
      : undefined;
  const selectedArtifactResult = selectedCamera
    ? inspectArtifactResultsByCameraId[selectedCamera.cameraId]
    : undefined;
  selectedModalCameraIdRef.current = selectedCamera?.cameraId;

  const retireModalInspectSnapshot = useCallback(() => {
    const currentSnapshot = modalInspectSnapshotRef.current;
    if (currentSnapshot) {
      retiredInspectSnapshotsRef.current.push(currentSnapshot);
      modalInspectSnapshotRef.current = undefined;
    }
  }, []);

  const resetCameraInspectionOrdering = useCallback((cameraId: number) => {
      delete latestInspectResultByCameraIdRef.current[cameraId];
      delete latestArtifactResultByCameraIdRef.current[cameraId];
      awaitingLiveResultAfterResetByCameraIdRef.current[cameraId] = true;
  }, []);

  const loadLatestInspectSnapshot = useCallback(async () => {
    if (activeInspectResultRef.current) {
      return;
    }

    const inspectResult = pendingInspectResultRef.current;
    const cameraId = selectedModalCameraIdRef.current;
    if (!inspectResult || cameraId === undefined || inspectResult.camera_id !== cameraId) {
      return;
    }

    activeInspectResultRef.current = inspectResult;
    const controller = new AbortController();
    inspectSnapshotLoadControllerRef.current = controller;
    setModalInspectSnapshotLoadState({ state: "loading" });

    try {
      const snapshot = await freezeInspectSnapshot(inspectResult, controller.signal);
      if (controller.signal.aborted || selectedModalCameraIdRef.current !== cameraId) {
        revokeInspectSnapshot(snapshot);
        return;
      }

      retireModalInspectSnapshot();
      modalInspectSnapshotRef.current = snapshot;
      setModalInspectSnapshot(snapshot);
      setModalInspectSnapshotLoadState({ state: "idle" });
    } catch (error) {
      const ownsActiveLoad =
        activeInspectResultRef.current === inspectResult &&
        inspectSnapshotLoadControllerRef.current === controller;
      if (
        ownsActiveLoad &&
        selectedModalCameraIdRef.current === cameraId &&
        !(error instanceof DOMException && error.name === "AbortError")
      ) {
        setModalInspectSnapshotLoadState({
          state: "error",
          message: error instanceof Error ? error.message : "Failed to load inspect snapshot",
        });
      }
    } finally {
      const ownsActiveLoad =
        activeInspectResultRef.current === inspectResult &&
        inspectSnapshotLoadControllerRef.current === controller;
      if (ownsActiveLoad) {
        activeInspectResultRef.current = undefined;
        inspectSnapshotLoadControllerRef.current = undefined;

        const pendingResult = pendingInspectResultRef.current;
        if (
          pendingResult &&
          selectedModalCameraIdRef.current === cameraId &&
          !isSameInspectResult(pendingResult, inspectResult)
        ) {
          void loadLatestInspectSnapshot();
        }
      }
    }
  }, [retireModalInspectSnapshot]);

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
      const hasArtifacts = hasCompleteInspectArtifacts(inspectResult);

      if (hasArtifacts) {
        if (awaitingLiveResultAfterResetByCameraIdRef.current[cameraId]) {
          return;
        }
        const currentLiveResult = latestInspectResultByCameraIdRef.current[cameraId];
        const previousArtifactResult = latestArtifactResultByCameraIdRef.current[cameraId];
        if (previousArtifactResult && !isNewerSnapshot(inspectResult, previousArtifactResult)) {
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
      delete awaitingLiveResultAfterResetByCameraIdRef.current[cameraId];
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
      if (previewUpdateTimerRef.current !== null) {
        window.clearTimeout(previewUpdateTimerRef.current);
        previewUpdateTimerRef.current = null;
      }
      pendingPreviewUrlsByCameraIdRef.current = {};
    };
  }, [resetCameraInspectionOrdering]);

  useEffect(() => {
    const cameraId = selectedCamera?.cameraId;
    inspectSnapshotLoadControllerRef.current?.abort();
    inspectSnapshotLoadControllerRef.current = undefined;
    activeInspectResultRef.current = undefined;
    pendingInspectResultRef.current = undefined;
    setModalInspectSnapshotLoadState({ state: "idle" });

    if (cameraId === undefined) {
      retireModalInspectSnapshot();
      setModalInspectSnapshot(undefined);
      return;
    }

    const currentSnapshot = modalInspectSnapshotRef.current;
    if (currentSnapshot?.inspectResult.camera_id !== cameraId) {
      retireModalInspectSnapshot();
      setModalInspectSnapshot(undefined);
    }

    return () => inspectSnapshotLoadControllerRef.current?.abort();
  }, [retireModalInspectSnapshot, selectedCamera?.cameraId]);

  useEffect(() => {
    if (!selectedArtifactResult || selectedArtifactResult.camera_id !== selectedModalCameraIdRef.current) {
      return;
    }
    pendingInspectResultRef.current = selectedArtifactResult;

    const currentSnapshot = modalInspectSnapshotRef.current;
    if (currentSnapshot && isSameInspectResult(currentSnapshot.inspectResult, selectedArtifactResult)) {
      return;
    }
    void loadLatestInspectSnapshot();
  }, [loadLatestInspectSnapshot, selectedArtifactResult]);

  useEffect(() => {
    const retiredSnapshots = retiredInspectSnapshotsRef.current;
    retiredInspectSnapshotsRef.current = [];
    for (const snapshot of retiredSnapshots) {
      revokeInspectSnapshot(snapshot);
    }
  }, [modalInspectSnapshot]);

  useEffect(
    () => () => {
      selectedModalCameraIdRef.current = undefined;
      pendingInspectResultRef.current = undefined;
      activeInspectResultRef.current = undefined;
      if (modalInspectSnapshotRef.current) {
        revokeInspectSnapshot(modalInspectSnapshotRef.current);
        modalInspectSnapshotRef.current = undefined;
      }
      inspectSnapshotLoadControllerRef.current?.abort();
      inspectSnapshotLoadControllerRef.current = undefined;
      for (const snapshot of retiredInspectSnapshotsRef.current) {
        revokeInspectSnapshot(snapshot);
      }
      retiredInspectSnapshotsRef.current = [];
    },
    [],
  );

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
              const isInspectionEnabled = inspectionControlState?.isEnabled ?? true;
              const isInspectionActionPending =
                inspectionControlState?.state === "starting" || inspectionControlState?.state === "stopping";

              return (
                <StatusCard
                  key={camera.cameraId}
                  cameraId={camera.cameraId}
                  objectName={camera.objectName}
                  imageUrl={camera.imageUrl}
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
          cameraImageUrl={displayedModalInspectSnapshot?.imageUrl}
          inspectHeatmapUrl={displayedModalInspectSnapshot?.heatmapUrl}
          inspectSnapshotLoadState={modalInspectSnapshotLoadState}
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
          inspectResult={displayedModalInspectSnapshot?.inspectResult}
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

function isNewerSnapshot(candidate: InspectResultPayload, current: InspectResultPayload) {
  return compareInspectResults(candidate, current) > 0;
}

function isSameInspectResult(left: InspectResultPayload, right: InspectResultPayload) {
  return (
    left.camera_id === right.camera_id &&
    left.frame_id === right.frame_id &&
    left.server_ts_ms === right.server_ts_ms
  );
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

function hasCompleteInspectArtifacts(inspectResult: InspectResultPayload) {
  const bundleId = inspectResult.artifact_bundle_id;
  if (!bundleId) {
    return false;
  }
  const bundleBasePath = `/api/inspection-artifacts/${bundleId}/`;
  const imagePath = inspectResult.http_path ?? inspectResult.current.http_path;
  return Boolean(
    imagePath?.startsWith(bundleBasePath) &&
      inspectResult.heatmap &&
      inspectResult.heatmap.http_path?.startsWith(bundleBasePath),
  );
}

async function freezeInspectSnapshot(
  inspectResult: InspectResultPayload,
  signal: AbortSignal,
): Promise<InspectSnapshot> {
  const imagePath = inspectResult.http_path ?? inspectResult.current.http_path;
  const heatmap = inspectResult.heatmap;
  if (!imagePath || !heatmap) {
    throw new Error("Inspect result artifacts are incomplete");
  }

  const imageSourceUrl = orchestratorApi.imageUrl(imagePath, inspectResult.frame_id);
  const heatmapSourceUrl = resolveHeatmapSourceUrl(heatmap);
  const [imageBlob, heatmapBlob] = await Promise.all([
    fetchArtifactBlob(imageSourceUrl, "image/jpeg", signal),
    fetchArtifactBlob(heatmapSourceUrl, "application/octet-stream", signal),
  ]);

  return {
    inspectResult,
    imageUrl: URL.createObjectURL(imageBlob),
    heatmapUrl: URL.createObjectURL(heatmapBlob),
  };
}

function resolveHeatmapSourceUrl(heatmap: HeatmapDescriptor) {
  if (heatmap.http_path) {
    return orchestratorApi.url(heatmap.http_path);
  }
  if (heatmap.artifact_id) {
    return orchestratorApi.heatmapArtifactUrl(heatmap.artifact_id);
  }
  throw new Error("Heatmap source is missing");
}

async function fetchArtifactBlob(url: string, accept: string, signal: AbortSignal) {
  const response = await fetch(url, {
    cache: "no-store",
    headers: { Accept: accept },
    signal,
  });
  if (!response.ok) {
    throw new Error(`Failed to load inspect artifact: HTTP ${response.status}`);
  }
  return response.blob();
}

function revokeInspectSnapshot(snapshot: InspectSnapshot) {
  URL.revokeObjectURL(snapshot.imageUrl);
  URL.revokeObjectURL(snapshot.heatmapUrl);
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
