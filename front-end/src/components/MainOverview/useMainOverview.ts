import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { orchestratorApi } from "../../shared/api";
import type { InspectionStateResponse } from "../../shared/api/types";
import { isCaptureOnlyInspectResult, resolveInspectionResultState } from "../../shared/inspectResult";
import { errorMessage } from "../../shared/lib/errors";
import { compareFrameIds } from "../../shared/lib/frameIds";
import { getReferenceImagesSnapshot, subscribeReferenceImages } from "../../shared/referenceImages";
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
  isInspectionCounterReset,
  latestSnapshotToInspectResult,
  loadArchivedInspectionHistory,
  loadMainOverviewData,
  resolveInspectionId,
  selectModalInspection as selectModalInspectionSnapshot,
  setInspectionHistoryLimit,
  trimInspectionHistoryItems,
  upsertInspectionHistoryItem,
  upsertModalInspectionItem,
  updateModalSnapshotResult,
} from "./MainController";
import type {
  CameraImageUrlsById,
  InspectionControlState,
  InspectionHistoryItem,
  InspectionStats,
  ModalInspectionSnapshot,
  SelectedCamera,
} from "./type";

export function useMainOverview(inspectionResetVersion = 0) {
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
  const [inspectionStatsByCameraId, setInspectionStatsByCameraId] = useState<Record<number, InspectionHistoryItem[]>>(
    {},
  );
  const [archiveHistoryState, setArchiveHistoryState] = useState<"idle" | "loading" | "loaded" | "error">("idle");
  const [archiveHistoryMessage, setArchiveHistoryMessage] = useState<string | null>(null);
  const [archivedHistoryByCameraId, setArchivedHistoryByCameraId] = useState<Record<number, InspectionHistoryItem[]>>(
    {},
  );
  const [isArchiveViewerOpen, setIsArchiveViewerOpen] = useState(false);
  const archiveHistoryLoadingRef = useRef(false);
  const [inspectionControlByCameraId, setInspectionControlByCameraId] = useState<
    Record<number, InspectionControlState>
  >({});
  const [hasReference, setHasReference] = useState(false);
  const [referenceSnapshot, setReferenceSnapshot] = useState(getReferenceImagesSnapshot);
  const [inspectionStartedAtMs, setInspectionStartedAtMs] = useState<number | undefined>();
  const [inspectionStoppedAtMs, setInspectionStoppedAtMs] = useState<number | undefined>();
  const latestPreviewTimestampByCameraIdRef = useRef<Record<number, number>>({});
  const latestPreviewFrameIdByCameraIdRef = useRef<Record<number, string>>({});
  const latestInspectResultByCameraIdRef = useRef<Record<number, InspectResultPayload>>({});
  const latestArtifactResultByCameraIdRef = useRef<Record<number, InspectResultPayload>>({});
  const latestInspectionIdByCameraIdRef = useRef<Record<number, number>>({});
  const inspectionEnabledByCameraIdRef = useRef<Record<number, boolean>>({});
  const inspectionAcceptedAfterMsByCameraIdRef = useRef<Record<number, number>>({});
  const inspectionAcceptedFromFrameIdByCameraIdRef = useRef<Record<number, string>>({});
  const pendingPreviewUrlsByCameraIdRef = useRef<CameraImageUrlsById>({});
  const previewUpdateFrameRef = useRef<number | null>(null);

  const resetCameraInspectionOrdering = useCallback((cameraId: number) => {
    delete latestInspectResultByCameraIdRef.current[cameraId];
    delete latestArtifactResultByCameraIdRef.current[cameraId];
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
      if (!hasReference) {
        return;
      }
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
          message: nextEnabled ? "Запуск инспекции..." : "Остановка инспекции...",
        },
      }));

      try {
        const response = await orchestratorApi.setInspectionEnabled(cameraId, nextEnabled);
        if (response.unknownCameraIds.includes(cameraId)) {
          throw new Error(`Камера ${cameraId} не настроена`);
        }

        const isEnabled = response.enabledCameraIds.includes(cameraId);
        const changedAtMs = Date.now();
        if (isEnabled) {
          setInspectionStartedAtMs(changedAtMs);
          inspectionEnabledByCameraIdRef.current[cameraId] = true;
          inspectionAcceptedAfterMsByCameraIdRef.current[cameraId] = changedAtMs;
          const resumeFrameId = latestPreviewFrameIdByCameraIdRef.current[cameraId];
          if (resumeFrameId !== undefined) {
            inspectionAcceptedFromFrameIdByCameraIdRef.current[cameraId] = resumeFrameId;
          }
        } else {
          setInspectionStoppedAtMs(changedAtMs);
          inspectionEnabledByCameraIdRef.current[cameraId] = false;
        }
        setPreviewImagesEnabled(Object.values(inspectionEnabledByCameraIdRef.current).some((enabled) => !enabled));
        setInspectionControlByCameraId((currentStates) => ({
          ...currentStates,
          [cameraId]: {
            isEnabled,
            state: "idle",
            message: isEnabled ? "Инспекция включена" : "Инспекция остановлена",
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
    [hasReference, inspectionControlByCameraId],
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

  const loadArchivedHistory = useCallback(
    async (targetCameraIds: number[] = cameraIds) => {
      if (targetCameraIds.length === 0 || archiveHistoryLoadingRef.current) {
        return;
      }

      archiveHistoryLoadingRef.current = true;
      setArchiveHistoryState("loading");
      setArchiveHistoryMessage(null);
      try {
        const { historyByCameraId, failedCameraIds } = await loadArchivedInspectionHistory(targetCameraIds);
        setArchivedHistoryByCameraId(historyByCameraId);
        setIsArchiveViewerOpen(true);
        setArchiveHistoryState("loaded");
        const frameCount = Object.values(historyByCameraId).reduce((sum, items) => sum + items.length, 0);
        setArchiveHistoryMessage(
          failedCameraIds.length > 0
            ? `Архив открыт частично: недоступны камеры ${failedCameraIds.join(", ")}`
            : frameCount > 0
              ? `Архив открыт: ${frameCount} кадров`
              : "Архив пуст — кадры ещё не сохранены",
        );
      } catch (error) {
        setArchiveHistoryState("error");
        setArchiveHistoryMessage(errorMessage(error));
      } finally {
        archiveHistoryLoadingRef.current = false;
      }
    },
    [cameraIds],
  );

  const closeArchiveViewer = useCallback(() => {
    setIsArchiveViewerOpen(false);
  }, []);

  useEffect(() => subscribeReferenceImages(() => setReferenceSnapshot(getReferenceImagesSnapshot())), []);

  useEffect(() => {
    if (inspectionResetVersion <= 0) {
      return;
    }

    let cancelled = false;
    void orchestratorApi.getInspectionStatus().then((inspectionStatus) => {
      if (cancelled) {
        return;
      }
      const changedAtMs = Date.now();
      for (const cameraId of inspectionStatus.enabledCameraIds) {
        if (inspectionEnabledByCameraIdRef.current[cameraId] !== true) {
          inspectionAcceptedAfterMsByCameraIdRef.current[cameraId] = changedAtMs;
          const resumeFrameId = latestPreviewFrameIdByCameraIdRef.current[cameraId];
          if (resumeFrameId !== undefined) {
            inspectionAcceptedFromFrameIdByCameraIdRef.current[cameraId] = resumeFrameId;
          }
        }
        inspectionEnabledByCameraIdRef.current[cameraId] = true;
      }
      for (const cameraId of inspectionStatus.disabledCameraIds) {
        inspectionEnabledByCameraIdRef.current[cameraId] = false;
      }
      setInspectionControlByCameraId(createInspectionControlStates(inspectionStatus));
      setPreviewImagesEnabled(inspectionStatus.disabledCameraIds.length > 0);
      if (inspectionStatus.enabledCameraIds.length > 0) {
        setInspectionStartedAtMs(changedAtMs);
        setInspectionStoppedAtMs(undefined);
      } else {
        setInspectionStoppedAtMs(changedAtMs);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [inspectionResetVersion]);

  const inspectionStats = useMemo(
    () =>
      createInspectionStats(
        inspectionStatsByCameraId,
        cameraIds,
        referenceSnapshot,
        inspectionStartedAtMs,
        inspectionStoppedAtMs,
      ),
    [cameraIds, inspectionStartedAtMs, inspectionStatsByCameraId, inspectionStoppedAtMs, referenceSnapshot],
  );

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
        setInspectionStatsByCameraId,
        latestPreviewTimestampByCameraIdRef,
        latestPreviewFrameIdByCameraIdRef,
        latestInspectResultByCameraIdRef,
        latestArtifactResultByCameraIdRef,
      });
      if (inspectionStatus) {
        inspectionEnabledByCameraIdRef.current = Object.fromEntries([
          ...inspectionStatus.enabledCameraIds.map((cameraId) => [cameraId, true] as const),
          ...inspectionStatus.disabledCameraIds.map((cameraId) => [cameraId, false] as const),
        ]);
        setInspectionControlByCameraId(createInspectionControlStates(inspectionStatus));
        setPreviewImagesEnabled(inspectionStatus.disabledCameraIds.length > 0);
      }
    });

    return () => {
      isActive = false;
    };
  }, []);

  useEffect(() => {
    const handleGlobalInspectionControl = (event: Event) => {
      const response = (event as CustomEvent<InspectionStateResponse>).detail;
      if (!response) return;
      const changedAtMs = Date.now();
      const enabled = new Set(response.enabledCameraIds);
      for (const cameraId of response.requestedCameraIds) {
        const isEnabled = enabled.has(cameraId);
        inspectionEnabledByCameraIdRef.current[cameraId] = isEnabled;
        if (isEnabled) {
          inspectionAcceptedAfterMsByCameraIdRef.current[cameraId] = changedAtMs;
          const latestPreviewFrameId = latestPreviewFrameIdByCameraIdRef.current[cameraId];
          if (latestPreviewFrameId !== undefined) {
            inspectionAcceptedFromFrameIdByCameraIdRef.current[cameraId] = latestPreviewFrameId;
          }
        }
      }
      setInspectionControlByCameraId(createInspectionControlStates(response));
      setPreviewImagesEnabled(response.disabledCameraIds.length > 0);
      if (response.enabledCameraIds.length > 0) {
        setInspectionStartedAtMs(changedAtMs);
      } else {
        setInspectionStoppedAtMs(changedAtMs);
      }
    };
    window.addEventListener("inspection-control-changed", handleGlobalInspectionControl);
    return () => window.removeEventListener("inspection-control-changed", handleGlobalInspectionControl);
  }, []);

  useEffect(() => {
    const unsubscribeMessage = orchestratorWs.onMessage((message) => {
      if (message.type === "server.hello" || message.type === "server.state") {
        const nextHasReference = message.payload.session_state !== "NO_REFERENCE";
        setHasReference(nextHasReference);
        const hasDisabledInspection = Object.values(inspectionEnabledByCameraIdRef.current).some(
          (enabled) => !enabled,
        );
        setPreviewImagesEnabled(!nextHasReference || hasDisabledInspection);
        return;
      }

      if (message.type === "server.reference_bundle_ack" && message.payload.ok) {
        setHasReference(true);
        setPreviewImagesEnabled(Object.values(inspectionEnabledByCameraIdRef.current).some((enabled) => !enabled));
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
        applyBucketResult(
          message.payload,
          setInspectionHistoryByCameraId,
          setInspectionStatsByCameraId,
          latestInspectResultByCameraIdRef,
          (cameraId, serverTsMs, frameId) =>
            shouldAcceptInspectionResult(
              cameraId,
              serverTsMs,
              frameId,
              inspectionEnabledByCameraIdRef,
              inspectionAcceptedAfterMsByCameraIdRef,
              inspectionAcceptedFromFrameIdByCameraIdRef,
            ),
        );
        return;
      }

      if (message.type !== "server.inspect_result") {
        return;
      }

      const inspectResult = message.payload;
      const cameraId = inspectResult.camera_id;

      // Preview-only captures remain visible while inspection is globally stopped.
      if (isCaptureOnlyInspectResult(inspectResult)) {
        // После повторного пуска не даём запоздавшему preview-only результату
        // из остановленного интервала перезаписать первый новый результат инспекции.
        if (
          inspectionEnabledByCameraIdRef.current[cameraId] === true &&
          !shouldAcceptInspectionResult(
            cameraId,
            inspectResult.server_ts_ms,
            inspectResult.frame_id,
            inspectionEnabledByCameraIdRef,
            inspectionAcceptedAfterMsByCameraIdRef,
            inspectionAcceptedFromFrameIdByCameraIdRef,
          )
        ) {
          return;
        }
        applyCaptureOnlyInspectResult(
          inspectResult,
          latestInspectResultByCameraIdRef,
          setInspectResultsByCameraId,
          setPreviewFrameIdsByCameraId,
          setPreviewImageUrlsByCameraId,
          setInspectionHistoryByCameraId,
          setInspectionStatsByCameraId,
        );
        return;
      }

      if (
        !shouldAcceptInspectionResult(
          cameraId,
          inspectResult.server_ts_ms,
          inspectResult.frame_id,
          inspectionEnabledByCameraIdRef,
          inspectionAcceptedAfterMsByCameraIdRef,
          inspectionAcceptedFromFrameIdByCameraIdRef,
        )
      ) {
        return;
      }

      logMissingInspectionResults(latestInspectionIdByCameraIdRef, inspectResult);
      setHasReference(true);
      addInspectionHistoryItem(setInspectionHistoryByCameraId, inspectResult);
      addInspectionStatsItem(setInspectionStatsByCameraId, inspectResult);
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
    archivedHistoryByCameraId,
    isArchiveViewerOpen,
    inspectionControlByCameraId,
    archiveHistoryState,
    archiveHistoryMessage,
    hasReference,
    inspectionStats,
    toggleInspection,
    loadArchivedHistory,
    closeArchiveViewer,
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

function shouldAcceptInspectionResult(
  cameraId: number,
  serverTsMs: number,
  frameId: string,
  enabledByCameraIdRef: React.MutableRefObject<Record<number, boolean>>,
  acceptedAfterMsByCameraIdRef: React.MutableRefObject<Record<number, number>>,
  acceptedFromFrameIdByCameraIdRef: React.MutableRefObject<Record<number, string>>,
) {
  if (enabledByCameraIdRef.current[cameraId] === false) {
    return false;
  }

  const acceptedAfterMs = acceptedAfterMsByCameraIdRef.current[cameraId];
  if (acceptedAfterMs !== undefined && serverTsMs < acceptedAfterMs) {
    return false;
  }

  const acceptedFromFrameId = acceptedFromFrameIdByCameraIdRef.current[cameraId];
  return acceptedFromFrameId === undefined || compareFrameIds(frameId, acceptedFromFrameId) > 0;
}

function applyBucketResult(
  bucket: InspectBucketResultPayload,
  setHistory: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  setStats: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  latestInspectResultByCameraIdRef: React.MutableRefObject<Record<number, InspectResultPayload>>,
  shouldAccept: (cameraId: number, serverTsMs: number, frameId: string) => boolean,
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
    if (!shouldAccept(frame.camera_id, bucket.server_ts_ms, frame.frame_id)) {
      continue;
    }

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
        }),
      };
    });
    addInspectionStatsItem(setStats, inspectResult);
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
  setInspectionStatsByCameraId: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
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

  setPreviewFrameIdsByCameraId((previousFrameIds) => ({
    ...previousFrameIds,
    [cameraId]: merged.frame_id,
  }));

  const imageUrl = createWsFrameImageUrl(merged);
  if (imageUrl) {
    setPreviewImageUrlsByCameraId((previousImageUrls) => ({
      ...previousImageUrls,
      [cameraId]: imageUrl,
    }));
  }

  addInspectionHistoryItem(setInspectionHistoryByCameraId, merged);
  addInspectionStatsItem(setInspectionStatsByCameraId, merged);
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
      }),
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
    setInspectionStatsByCameraId: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>;
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
  mergeInspectionStats(deps.setInspectionStatsByCameraId, inspectionHistory);
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
      merged[cameraId] = trimInspectionHistoryItems(
        snapshotItems.reduce((items, item) => upsertInspectionHistoryItem(items, item), current[cameraId] ?? []),
      );
    }
    return merged;
  });
}

function createInspectionStats(
  historyByCameraId: Record<number, InspectionHistoryItem[]>,
  cameraIds: number[],
  referenceSnapshot: ReturnType<typeof getReferenceImagesSnapshot>,
  inspectionStartedAtMs: number | undefined,
  inspectionStoppedAtMs: number | undefined,
): InspectionStats {
  const totals = createInspectionStatsCounts(historyByCameraId);
  const groups = createInspectionStatsGroups(historyByCameraId, cameraIds);
  const reference = referenceSnapshot
    .filter((item) => item.frame.frame_id !== undefined)
    .sort((left, right) => {
      const byTime = (left.committedAtMs ?? 0) - (right.committedAtMs ?? 0);
      return byTime !== 0 ? byTime : left.cameraId - right.cameraId;
    })[0];

  return {
    total: totals.total,
    passed: totals.passed,
    failed: totals.failed,
    groups,
    referenceFrameId: reference ? String(reference.frame.frame_id) : undefined,
    referenceSetAtMs: reference?.committedAtMs,
    inspectionStartedAtMs,
    inspectionStoppedAtMs,
  };
}

function createInspectionStatsGroups(historyByCameraId: Record<number, InspectionHistoryItem[]>, cameraIds: number[]) {
  return chunkItems(cameraIds, 5)
    .slice(0, 2)
    .map((groupCameraIds, index) => {
      const groupHistory = Object.fromEntries(
        groupCameraIds.map((cameraId) => [cameraId, historyByCameraId[cameraId] ?? []]),
      );
      const counts = createInspectionStatsCounts(groupHistory);
      return {
        id: `group-${index + 1}`,
        label: `Группа ${index + 1}`,
        cameraIds: groupCameraIds,
        ...counts,
      };
    });
}

function mergeInspectionStats(
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
      const currentItems = merged[cameraId] ?? [];
      merged[cameraId] = snapshotItems.reduce((items, item) => {
        if (item.result === "capture") {
          return items;
        }
        return [item, ...items.filter((existingItem) => existingItem.frameId !== item.frameId)];
      }, currentItems);
    }
    return merged;
  });
}

function addInspectionStatsItem(
  setStats: Dispatch<SetStateAction<Record<number, InspectionHistoryItem[]>>>,
  inspectResult: InspectResultPayload,
) {
  const result = resolveInspectionResultState(inspectResult);
  if (!result || result === "capture") {
    return;
  }

  setStats((current) => {
    const cameraStats = current[inspectResult.camera_id] ?? [];
    const nextItem = {
      frameId: inspectResult.frame_id,
      inspectionId: resolveInspectionId(inspectResult),
      result,
      inspectResult,
    };
    return {
      ...current,
      [inspectResult.camera_id]: [nextItem, ...cameraStats.filter((item) => item.frameId !== nextItem.frameId)],
    };
  });
}

function createInspectionStatsCounts(historyByCameraId: Record<number, InspectionHistoryItem[]>) {
  const decisionsByInspectionId = new Map<string, "pass" | "fail">();

  Object.values(historyByCameraId)
    .flat()
    .forEach((item) => {
      if (item.result === "capture") {
        return;
      }

      const inspectionId = item.inspectResult.inspection_id ?? item.inspectionId ?? item.frameId;
      const currentResult = decisionsByInspectionId.get(inspectionId);
      decisionsByInspectionId.set(inspectionId, currentResult === "fail" || item.result === "fail" ? "fail" : "pass");
    });

  const failed = Array.from(decisionsByInspectionId.values()).filter((result) => result === "fail").length;
  const passed = decisionsByInspectionId.size - failed;
  return {
    total: passed + failed,
    passed,
    failed,
  };
}

function chunkItems<T>(items: T[], chunkSize: number) {
  return Array.from({ length: Math.ceil(items.length / chunkSize) }, (_, groupIndex) => {
    const startIndex = groupIndex * chunkSize;
    return items.slice(startIndex, startIndex + chunkSize);
  });
}
