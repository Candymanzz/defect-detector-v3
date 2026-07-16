import { useCallback, useEffect, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { orchestratorApi } from "../../shared/api";
import { isCaptureOnlyInspectResult, resolveInspectionResultState } from "../../shared/inspectResult";
import { errorMessage } from "../../shared/lib/errors";
import { compareFrameIds } from "../../shared/lib/frameIds";
import { orchestratorWs } from "../../shared/ws";
import type { InspectResultPayload, InspectBucketResultPayload, PreviewFramePayload } from "../../shared/ws";
import {
  compareInspectResults,
  createInspectionControlStates,
  createMainOverviewErrorData,
  createModalInspectionSnapshot,
  createWsFrameImageUrl,
  FALLBACK_CAMERA_IDS,
  hasDisplayableInspectImage,
  hasImmutableInspectArtifact,
  inspectionHistoryLimit,
  isInspectionCounterReset,
  latestSnapshotToInspectResult,
  loadArchivedInspectionHistory,
  loadMainOverviewData,
  resolveInspectionId,
  selectModalInspection as selectModalInspectionSnapshot,
  setInspectionHistoryLimit,
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

  const selectModalInspection = useCallback((frameId: string) => {
    setModalSnapshot((currentSnapshot) => selectModalInspectionSnapshot(currentSnapshot, frameId));
  }, []);

  const closeInspectionModal = useCallback(() => setModalSnapshot(null), []);

  useEffect(() => {
    let isActive = true;

    Promise.all([
      loadMainOverviewData().catch(createMainOverviewErrorData),
      orchestratorApi.getInspectionStatus().catch(() => null),
      orchestratorApi.getFrameArchiveSettings().catch(() => null),
    ]).then(([overviewData, inspectionStatus, frameArchiveSettings]) => {
      if (!isActive) {
        return;
      }

      if (frameArchiveSettings?.max_frames_per_camera != null) {
        setInspectionHistoryLimit(frameArchiveSettings.max_frames_per_camera);
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

      void loadArchivedInspectionHistory(overviewData.cameraIds)
        .then((archivedHistory) => {
          if (!isActive || Object.keys(archivedHistory).length === 0) {
            return;
          }
          mergeInspectionHistory(setInspectionHistoryByCameraId, archivedHistory);
        })
        .catch(() => undefined);
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
        applyPreviewFrames(
          [message.payload],
          latestPreviewFrameIdByCameraIdRef,
          latestPreviewTimestampByCameraIdRef,
          setPreviewFrameIdsByCameraId,
          pendingPreviewUrlsByCameraIdRef,
          previewUpdateFrameRef,
          setPreviewImageUrlsByCameraId,
          resetCameraInspectionOrdering,
        );
        return;
      }

      if (message.type === "server.preview_batch") {
        applyPreviewFrames(
          message.payload.frames,
          latestPreviewFrameIdByCameraIdRef,
          latestPreviewTimestampByCameraIdRef,
          setPreviewFrameIdsByCameraId,
          pendingPreviewUrlsByCameraIdRef,
          previewUpdateFrameRef,
          setPreviewImageUrlsByCameraId,
          resetCameraInspectionOrdering,
        );
        return;
      }

      if (message.type === "server.inspect_bucket_result") {
        applyBucketResult(message.payload, setInspectionHistoryByCameraId, latestInspectResultByCameraIdRef);
        return;
      }

      if (message.type !== "server.inspect_result") {
        return;
      }

      const inspectResult = message.payload;
      const cameraId = inspectResult.camera_id;

      if (isCaptureOnlyInspectResult(inspectResult)) {
        applyCaptureOnlyInspectResult(
          inspectResult,
          latestInspectResultByCameraIdRef,
          setInspectResultsByCameraId,
          setPreviewFrameIdsByCameraId,
          setPreviewImageUrlsByCameraId,
          setInspectionHistoryByCameraId,
        );
        return;
      }

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

function applyPreviewFrames(
  frames: PreviewFramePayload[],
  latestPreviewFrameIdByCameraIdRef: React.MutableRefObject<Record<number, string>>,
  latestPreviewTimestampByCameraIdRef: React.MutableRefObject<Record<number, number>>,
  setPreviewFrameIdsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
  pendingPreviewUrlsByCameraIdRef: React.MutableRefObject<CameraImageUrlsById>,
  previewUpdateFrameRef: React.MutableRefObject<number | null>,
  setPreviewImageUrlsByCameraId: Dispatch<SetStateAction<CameraImageUrlsById>>,
  resetCameraInspectionOrdering: (cameraId: number) => void,
) {
  if (frames.length === 0) {
    return;
  }

  const nextFrameIds: Record<number, string> = {};
  for (const previewFrame of frames) {
    const cameraId = previewFrame.camera_id;
    const previousTimestamp = latestPreviewTimestampByCameraIdRef.current[cameraId] ?? 0;
    if (previewFrame.server_ts_ms < previousTimestamp) {
      continue;
    }

    const previousFrameId = latestPreviewFrameIdByCameraIdRef.current[cameraId];
    if (previousFrameId && compareFrameIds(previewFrame.frame_id, previousFrameId) < 0) {
      resetCameraInspectionOrdering(cameraId);
    }
    latestPreviewFrameIdByCameraIdRef.current[cameraId] = previewFrame.frame_id;
    latestPreviewTimestampByCameraIdRef.current[cameraId] = previewFrame.server_ts_ms;
    nextFrameIds[cameraId] = previewFrame.frame_id;

    const imageUrl = createWsFrameImageUrl(previewFrame);
    if (imageUrl) {
      pendingPreviewUrlsByCameraIdRef.current[cameraId] = imageUrl;
    }
  }

  if (Object.keys(nextFrameIds).length > 0) {
    setPreviewFrameIdsByCameraId((previousFrameIds) => ({
      ...previousFrameIds,
      ...nextFrameIds,
    }));
  }

  if (Object.keys(pendingPreviewUrlsByCameraIdRef.current).length === 0) {
    return;
  }

  if (previewUpdateFrameRef.current !== null) {
    return;
  }

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

function removeCameraResult<T>(results: Record<number, T>, cameraId: number) {
  if (!(cameraId in results)) {
    return results;
  }

  const nextResults = { ...results };
  delete nextResults[cameraId];
  return nextResults;
}

function applyBucketResult(
  bucket: InspectBucketResultPayload,
  setHistory: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  latestInspectResultByCameraIdRef: React.MutableRefObject<Record<number, InspectResultPayload>>,
) {
  const inspectionId = String(bucket.trigger_sequence);
  const bucketResult: "pass" | "fail" | "capture" = bucket.frames.every(
    (frame) => frame.action === "CAPTURE" || frame.python_status === "NO_REFERENCE",
  )
    ? "capture"
    : bucket.overall_pass
      ? "pass"
      : "fail";

  for (const frame of bucket.frames) {
    const previous = latestInspectResultByCameraIdRef.current[frame.camera_id];
    const inspectResult: InspectResultPayload =
      previous && compareFrameIds(previous.frame_id, frame.frame_id) === 0
        ? {
            ...previous,
            inspection_id: inspectionId,
            overall_pass: frame.overall_pass,
            action: frame.action,
            anomaly_score: frame.anomaly_score,
            python_status: frame.python_status,
            geometry_status: frame.geometry_status,
            server_ts_ms: Math.max(previous.server_ts_ms, bucket.server_ts_ms),
          }
        : {
            camera_id: frame.camera_id,
            frame_id: frame.frame_id,
            inspection_id: inspectionId,
            session_state: bucket.session_state,
            current: {
              camera_id: frame.camera_id,
              frame_id: frame.frame_id,
              shm_name: "",
              width: 0,
              height: 0,
              stride: 0,
              shm_offset: 0,
              pixel_format: "bgr_u8",
              channels: 3,
            },
            heatmap: null,
            active_reference_view_index: 0,
            detector: {},
            overall_pass: frame.overall_pass,
            action: frame.action,
            anomaly_score: frame.anomaly_score,
            python_status: frame.python_status,
            geometry_status: frame.geometry_status,
            fp_zones: [],
            server_ts_ms: bucket.server_ts_ms,
          };

    const resultState = resolveInspectionResultState(inspectResult) ?? bucketResult;
    setHistory((current) => {
      const cameraHistory = current[frame.camera_id] ?? [];
      return {
        ...current,
        [frame.camera_id]: upsertInspectionHistoryItem(cameraHistory, {
          frameId: frame.frame_id,
          inspectionId,
          result: resultState,
          inspectResult,
        }).slice(0, inspectionHistoryLimit),
      };
    });
  }
}

function mergeCaptureOnlyInspectResult(
  previous: InspectResultPayload,
  incoming: InspectResultPayload,
): InspectResultPayload {
  const mergedCurrent = {
    ...previous.current,
    ...incoming.current,
    http_path: incoming.current?.http_path ?? previous.current?.http_path,
  };
  return {
    ...previous,
    ...incoming,
    current: mergedCurrent,
    http_path: incoming.http_path ?? previous.http_path,
    artifact_bundle_id: incoming.artifact_bundle_id ?? previous.artifact_bundle_id,
  };
}

function applyCaptureOnlyInspectResult(
  inspectResult: InspectResultPayload,
  latestInspectResultByCameraIdRef: React.MutableRefObject<Record<number, InspectResultPayload>>,
  setInspectResultsByCameraId: Dispatch<SetStateAction<Record<number, InspectResultPayload>>>,
  setPreviewFrameIdsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
  setPreviewImageUrlsByCameraId: Dispatch<SetStateAction<CameraImageUrlsById>>,
  setInspectionHistoryByCameraId: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
) {
  const cameraId = inspectResult.camera_id;
  const previous = latestInspectResultByCameraIdRef.current[cameraId];
  if (
    previous &&
    compareFrameIds(inspectResult.frame_id, previous.frame_id) < 0 &&
    !hasDisplayableInspectImage(inspectResult)
  ) {
    return;
  }

  const merged =
    previous && compareFrameIds(previous.frame_id, inspectResult.frame_id) === 0
      ? mergeCaptureOnlyInspectResult(previous, inspectResult)
      : inspectResult;

  latestInspectResultByCameraIdRef.current[cameraId] = merged;
  setInspectResultsByCameraId((previousResults) => ({
    ...previousResults,
    [cameraId]: merged,
  }));

  const imageUrl = createWsFrameImageUrl(merged);
  if (imageUrl) {
    setPreviewFrameIdsByCameraId((previousFrameIds) => ({
      ...previousFrameIds,
      [cameraId]: merged.frame_id,
    }));
    setPreviewImageUrlsByCameraId((previousImageUrls) => ({
      ...previousImageUrls,
      [cameraId]: imageUrl,
    }));
  }

  addInspectionHistoryItem(setInspectionHistoryByCameraId, merged);
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
      }).slice(0, inspectionHistoryLimit),
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
      currentSnapshot.inspectResult?.frame_id === inspectResult.frame_id &&
      hasDisplayableInspectImage(inspectResult) &&
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
        .slice(0, inspectionHistoryLimit);
    }
    return merged;
  });
}
