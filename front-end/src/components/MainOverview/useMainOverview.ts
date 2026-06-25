import { useCallback, useEffect, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { orchestratorApi } from "../../shared/api";
import { resolveInspectionResultState } from "../../shared/inspectResult";
import { errorMessage } from "../../shared/lib/errors";
import { compareFrameIds } from "../../shared/lib/frameIds";
import { orchestratorWs } from "../../shared/ws";
import type { InspectResultPayload } from "../../shared/ws";
import {
  compareInspectResults,
  createInspectionControlStates,
  createInspectionResultKey,
  createMainOverviewErrorData,
  createModalInspectionSnapshot,
  createWsFrameImageUrl,
  FALLBACK_CAMERA_IDS,
  hasDisplayableInspectImage,
  hasImmutableInspectArtifact,
  INSPECTION_HISTORY_LIMIT,
  isInspectionCounterReset,
  latestSnapshotToInspectResult,
  loadMainOverviewData,
  resolveInspectionId,
  selectModalInspection as selectModalInspectionSnapshot,
  upsertInspectionHistoryItem,
  upsertModalInspectionItem,
  updateModalSnapshotResult,
} from "./MainController";
import type {
  CameraImageUrlsById,
  InspectionControlState,
  InspectionHistoryItem,
  ModalInspectionSnapshot,
  SelectedCamera,
} from "./type";

export function useMainOverview() {
  const [cameraIds, setCameraIds] = useState<number[]>(FALLBACK_CAMERA_IDS);
  const [modalSnapshot, setModalSnapshot] = useState<ModalInspectionSnapshot | null>(null);
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
  const latestInspectionIdByCameraIdRef = useRef<Record<number, number>>({});
  const pendingPreviewUrlsByCameraIdRef = useRef<CameraImageUrlsById>({});
  const previewUpdateFrameRef = useRef<number | null>(null);

  const resetCameraInspectionOrdering = useCallback((cameraId: number) => {
    delete latestInspectResultByCameraIdRef.current[cameraId];
    delete latestArtifactResultByCameraIdRef.current[cameraId];
    delete latestInspectionIdByCameraIdRef.current[cameraId];
    setInspectResultsByCameraId((previousResults) => removeCameraResult(previousResults, cameraId));
    setInspectArtifactResultsByCameraId((previousResults) => removeCameraResult(previousResults, cameraId));
    setModalSnapshot((currentSnapshot) =>
      currentSnapshot?.cameraId === cameraId
        ? {
            ...currentSnapshot,
            initialFrameId: undefined,
            inspectResult: undefined,
            cameraImageUrl: undefined,
            heatmapUrl: undefined,
          }
        : currentSnapshot,
    );
  }, []);

  const toggleInspection = useCallback(
    async (cameraId: number) => {
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
    },
    [inspectionControlByCameraId],
  );

  const openInspectionModal = useCallback(
    (
      camera: SelectedCamera,
      inspectResult: InspectResultPayload | undefined,
      artifactInspectResult: InspectResultPayload | undefined,
      previewFrameId: string | undefined,
      previewImageUrl: string | undefined,
      inspectionHistory: InspectionHistoryItem[],
    ) => {
      setModalSnapshot(
        createModalInspectionSnapshot(
          camera,
          inspectResult,
          artifactInspectResult,
          previewFrameId,
          previewImageUrl,
          inspectionHistory,
        ),
      );
    },
    [],
  );

  const selectModalInspection = useCallback((inspectionKey: string) => {
    setModalSnapshot((currentSnapshot) => selectModalInspectionSnapshot(currentSnapshot, inspectionKey));
  }, []);

  const closeInspectionModal = useCallback(() => setModalSnapshot(null), []);

  useEffect(() => {
    let isActive = true;

    Promise.all([
      loadMainOverviewData().catch(createMainOverviewErrorData),
      orchestratorApi.getInspectionStatus().catch(() => null),
    ]).then(([overviewData, inspectionStatus]) => {
      if (!isActive) {
        return;
      }

      setCameraIds(overviewData.cameraIds);
      void hydrateCardsFromLatestSnapshots(overviewData.cameraIds, () => isActive, {
        setPreviewImageUrlsByCameraId,
        setPreviewFrameIdsByCameraId,
        setInspectResultsByCameraId,
        setInspectArtifactResultsByCameraId,
        setInspectionHistoryByCameraId,
        latestPreviewTimestampByCameraIdRef,
        latestPreviewFrameIdByCameraIdRef,
        latestInspectResultByCameraIdRef,
        latestArtifactResultByCameraIdRef,
      });
      if (inspectionStatus) {
        setInspectionControlByCameraId(createInspectionControlStates(inspectionStatus));
      }
    });

    return () => {
      isActive = false;
    };
  }, []);

  useEffect(() => {
    const unsubscribeMessage = orchestratorWs.onMessage((message) => {
      if (message.type === "server.hello" || message.type === "server.state") {
        const nextHasReference = message.payload.session_state !== "NO_REFERENCE";
        setHasReference(nextHasReference);
        setPreviewImagesEnabled(!nextHasReference);
        return;
      }

      if (message.type === "server.reference_bundle_ack" && message.payload.ok) {
        setHasReference(true);
        setPreviewImagesEnabled(false);
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
        latestPreviewTimestampByCameraIdRef.current[cameraId] = previewFrame.server_ts_ms;
        setPreviewFrameIdsByCameraId((previousFrameIds) => ({
          ...previousFrameIds,
          [cameraId]: previewFrame.frame_id,
        }));

        const imageUrl = createWsFrameImageUrl(previewFrame);
        if (imageUrl) {
          queuePreviewImageUpdate(
            cameraId,
            imageUrl,
            pendingPreviewUrlsByCameraIdRef,
            previewUpdateFrameRef,
            setPreviewImageUrlsByCameraId,
          );
        }
        return;
      }

      if (message.type !== "server.inspect_result") {
        return;
      }

      const inspectResult = message.payload;
      const cameraId = inspectResult.camera_id;
      logMissingInspectionResults(latestInspectionIdByCameraIdRef, inspectResult);
      setHasReference(true);
      addInspectionHistoryItem(setInspectionHistoryByCameraId, inspectResult);
      addModalInspectionItem(setModalSnapshot, inspectResult);

      const previousLiveResult = latestInspectResultByCameraIdRef.current[cameraId];
      if (
        !hasImmutableInspectArtifact(inspectResult) &&
        previousLiveResult &&
        isInspectionCounterReset(previousLiveResult, inspectResult)
      ) {
        resetCameraInspectionOrdering(cameraId);
      }

      if (hasImmutableInspectArtifact(inspectResult)) {
        const previousArtifactResult = latestArtifactResultByCameraIdRef.current[cameraId];
        if (previousArtifactResult && compareInspectResults(inspectResult, previousArtifactResult) <= 0) {
          return;
        }

        latestArtifactResultByCameraIdRef.current[cameraId] = inspectResult;
        setInspectArtifactResultsByCameraId((previousResults) => ({
          ...previousResults,
          [cameraId]: inspectResult,
        }));

        const currentLiveResult = latestInspectResultByCameraIdRef.current[cameraId];
        if (!currentLiveResult || compareInspectResults(inspectResult, currentLiveResult) >= 0) {
          latestInspectResultByCameraIdRef.current[cameraId] = inspectResult;
          setInspectResultsByCameraId((previousResults) => ({
            ...previousResults,
            [cameraId]: inspectResult,
          }));
        }
      } else {
        const currentResult = latestInspectResultByCameraIdRef.current[cameraId];
        if (currentResult && compareInspectResults(inspectResult, currentResult) < 0) {
          return;
        }
        latestInspectResultByCameraIdRef.current[cameraId] = inspectResult;
        setInspectResultsByCameraId((previousResults) => ({
          ...previousResults,
          [cameraId]: inspectResult,
        }));
      }

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

  return {
    cameraIds,
    modalSnapshot,
    previewImageUrlsByCameraId,
    previewFrameIdsByCameraId,
    inspectResultsByCameraId,
    inspectArtifactResultsByCameraId,
    inspectionHistoryByCameraId,
    inspectionControlByCameraId,
    hasReference,
    toggleInspection,
    openInspectionModal,
    selectModalInspection,
    closeInspectionModal,
  };
}

function logMissingInspectionResults(
  latestInspectionIdsRef: React.MutableRefObject<Record<number, number>>,
  inspectResult: InspectResultPayload,
) {
  const inspectionId = Number(inspectResult.inspection_id);
  if (!Number.isSafeInteger(inspectionId) || inspectionId <= 0) {
    return;
  }

  const cameraId = inspectResult.camera_id;
  const previousId = latestInspectionIdsRef.current[cameraId];
  if (previousId !== undefined && inspectionId > previousId + 1) {
    console.warn("Missing inspection results", {
      cameraId,
      expectedFrom: previousId + 1,
      expectedTo: inspectionId - 1,
      received: inspectionId,
    });
  }
  if (previousId === undefined || inspectionId > previousId) {
    latestInspectionIdsRef.current[cameraId] = inspectionId;
  }
}

function queuePreviewImageUpdate(
  cameraId: number,
  imageUrl: string,
  pendingUrlsRef: React.MutableRefObject<CameraImageUrlsById>,
  animationFrameRef: React.MutableRefObject<number | null>,
  setPreviewUrls: Dispatch<SetStateAction<CameraImageUrlsById>>,
) {
  pendingUrlsRef.current[cameraId] = imageUrl;
  if (animationFrameRef.current !== null) {
    return;
  }

  animationFrameRef.current = window.requestAnimationFrame(() => {
    animationFrameRef.current = null;
    const pendingPreviewUrls = pendingUrlsRef.current;
    pendingUrlsRef.current = {};
    setPreviewUrls((previousImageUrls) => ({
      ...previousImageUrls,
      ...pendingPreviewUrls,
    }));
  });
}

function removeCameraResult<T>(results: Record<number, T>, cameraId: number) {
  if (!(cameraId in results)) {
    return results;
  }

  const nextResults = { ...results };
  delete nextResults[cameraId];
  return nextResults;
}

function addInspectionHistoryItem(
  setHistory: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  inspectResult: InspectResultPayload,
) {
  const result = resolveInspectionResultState(inspectResult);
  if (!result) {
    return;
  }

  setHistory((current) => {
    const cameraHistory = current[inspectResult.camera_id] ?? [];
    return {
      ...current,
      [inspectResult.camera_id]: upsertInspectionHistoryItem(cameraHistory, {
        frameId: inspectResult.frame_id,
        inspectionId: resolveInspectionId(inspectResult),
        result,
        inspectResult,
      }).slice(0, INSPECTION_HISTORY_LIMIT),
    };
  });
}

function addModalInspectionItem(
  setModalSnapshot: Dispatch<SetStateAction<ModalInspectionSnapshot | null>>,
  inspectResult: InspectResultPayload,
) {
  const result = resolveInspectionResultState(inspectResult);
  if (!result) {
    return;
  }

  setModalSnapshot((currentSnapshot) => {
    if (!currentSnapshot || currentSnapshot.cameraId !== inspectResult.camera_id) {
      return currentSnapshot;
    }

    const nextItems = upsertModalInspectionItem(currentSnapshot.inspectionItems, {
      frameId: inspectResult.frame_id,
      inspectionId: resolveInspectionId(inspectResult),
      result,
      inspectResult,
    });
    const nextSnapshot = {
      ...currentSnapshot,
      inspectionItems: nextItems,
    };

    if (
      createInspectionResultKey(currentSnapshot.inspectResult) === createInspectionResultKey(inspectResult) &&
      hasDisplayableInspectImage(inspectResult) &&
      currentSnapshot.inspectResult &&
      !hasDisplayableInspectImage(currentSnapshot.inspectResult)
    ) {
      return updateModalSnapshotResult(nextSnapshot, inspectResult);
    }

    return nextSnapshot;
  });
}

function setPreviewImagesEnabled(enabled: boolean) {
  try {
    if (enabled) {
      orchestratorWs.enablePreviewImages();
    } else {
      orchestratorWs.disablePreviewImages();
    }
  } catch {
    // The next server state message will retry after reconnect.
  }
}

async function hydrateCardsFromLatestSnapshots(
  cameraIds: number[],
  isActive: () => boolean,
  deps: {
    setPreviewImageUrlsByCameraId: Dispatch<SetStateAction<CameraImageUrlsById>>;
    setPreviewFrameIdsByCameraId: Dispatch<SetStateAction<Record<number, string>>>;
    setInspectResultsByCameraId: Dispatch<SetStateAction<Record<number, InspectResultPayload>>>;
    setInspectArtifactResultsByCameraId: Dispatch<SetStateAction<Record<number, InspectResultPayload>>>;
    setInspectionHistoryByCameraId: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>;
    latestPreviewTimestampByCameraIdRef: React.MutableRefObject<Record<number, number>>;
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

    const currentPreviewTimestamp = deps.latestPreviewTimestampByCameraIdRef.current[snapshot.cameraId] ?? 0;
    if (
      snapshot.hasCurrent &&
      snapshot.currentJpeg?.path &&
      snapshot.frameId >= 0 &&
      snapshot.updatedAtMs >= currentPreviewTimestamp
    ) {
      const frameId = String(snapshot.frameId);
      previewUrls[snapshot.cameraId] = orchestratorApi.imageUrl(snapshot.currentJpeg.path, snapshot.frameId);
      previewFrameIds[snapshot.cameraId] = frameId;
      deps.latestPreviewTimestampByCameraIdRef.current[snapshot.cameraId] = snapshot.updatedAtMs;
      deps.latestPreviewFrameIdByCameraIdRef.current[snapshot.cameraId] = frameId;
    }

    const inspectResult = latestSnapshotToInspectResult(snapshot);
    const currentInspectResult = deps.latestInspectResultByCameraIdRef.current[snapshot.cameraId];
    if (
      !inspectResult ||
      (currentInspectResult &&
        (inspectResult.server_ts_ms < currentInspectResult.server_ts_ms ||
          (inspectResult.server_ts_ms === currentInspectResult.server_ts_ms &&
            compareInspectResults(inspectResult, currentInspectResult) < 0)))
    ) {
      continue;
    }

    inspectResults[snapshot.cameraId] = inspectResult;
    deps.latestInspectResultByCameraIdRef.current[snapshot.cameraId] = inspectResult;
    if (hasImmutableInspectArtifact(inspectResult)) {
      artifactResults[snapshot.cameraId] = inspectResult;
      deps.latestArtifactResultByCameraIdRef.current[snapshot.cameraId] = inspectResult;
    }
    const resultState = resolveInspectionResultState(inspectResult);
    if (resultState) {
      inspectionHistory[snapshot.cameraId] = [
        {
          frameId: inspectResult.frame_id,
          inspectionId: resolveInspectionId(inspectResult),
          result: resultState,
          inspectResult,
        },
      ];
    }
  }

  mergeRecordState(deps.setPreviewImageUrlsByCameraId, previewUrls);
  mergeRecordState(deps.setPreviewFrameIdsByCameraId, previewFrameIds);
  mergeRecordState(deps.setInspectResultsByCameraId, inspectResults);
  mergeRecordState(deps.setInspectArtifactResultsByCameraId, artifactResults);
  mergeInspectionHistory(deps.setInspectionHistoryByCameraId, inspectionHistory);
}

function mergeRecordState<T>(setter: Dispatch<SetStateAction<Record<number, T>>>, values: Record<number, T>) {
  if (Object.keys(values).length > 0) {
    setter((current) => ({ ...current, ...values }));
  }
}

function mergeInspectionHistory(
  setter: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  values: Record<number, InspectionHistoryItem[]>,
) {
  if (Object.keys(values).length === 0) {
    return;
  }

  setter((current) => {
    const merged = { ...current };
    for (const [cameraIdText, snapshotItems] of Object.entries(values)) {
      const cameraId = Number(cameraIdText);
      merged[cameraId] = snapshotItems
        .reduce((items, item) => upsertInspectionHistoryItem(items, item), current[cameraId] ?? [])
        .slice(0, INSPECTION_HISTORY_LIMIT);
    }
    return merged;
  });
}
