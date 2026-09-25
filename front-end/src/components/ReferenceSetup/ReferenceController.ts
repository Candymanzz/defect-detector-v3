import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { MutableRefObject } from "react";
import { orchestratorApi } from "../../shared/api";
import {
  getArchivedReferenceGroup,
  getReferenceImage,
  resolveReferenceBundleImages,
  stageReferenceBundleContours,
} from "../../shared/referenceImages";
import { orchestratorWs } from "../../shared/ws";
import type { ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFpZones } from "./useReferenceFpZones";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

const CAMERAS_PER_REFERENCE_GROUP = 5;
const REFERENCE_PREVIEW_PAUSE_TIMEOUT_MS = 15000;
const EMPTY_CAMERA_IDS: number[] = [];

export type ReferenceSubmissionState = {
  state: "pending" | "confirmed" | "rejected";
  cameraIds: number[];
  frameIdsByCameraId: Record<number, string>;
  submittedAtMs: number;
};

export type ReferenceGroupContext = {
  phaseId: number;
  groupId: number;
  cameraIds: number[];
};

export function useReferenceSetupController(onClose: () => void, initialCameraId: number | null = null) {
  const status = useSyncExternalStore(
    (onStoreChange) => orchestratorWs.onStatus(onStoreChange),
    () => orchestratorWs.snapshot,
    () => orchestratorWs.snapshot,
  );
  const [message, setMessage] = useState("Waiting for preview frames...");
  const [cameraIds, setCameraIds] = useState<number[]>([]);
  const [activeGroupIndex, setActiveGroupIndex] = useState(0);
  const [isNewReferenceMode, setIsNewReferenceMode] = useState(false);
  const [replacementCameraIds, setReplacementCameraIds] = useState<number[]>([]);
  const [referenceSubmission, setReferenceSubmission] = useState<ReferenceSubmissionState | null>(null);
  const referencePreviewResumeTimerRef = useRef<number | null>(null);
  const isReferencePreviewPausedRef = useRef(false);
  const hasReferenceRef = useRef(false);
  const pendingReferenceMessageIdsRef = useRef<Set<string>>(new Set());
  const pendingReferenceCameraIdsByMessageIdRef = useRef<Record<string, number[]>>({});
  const referenceCommitSyncRef = useRef<{
    cameraIds: number[];
    cameraGroups: number[][];
    loadStoredReferenceImages: (cameraIds?: number[]) => { loadedCameraIds: number[]; missingCameraIds: number[] };
    resetEditedFpZonesForCameraIds: (cameraIds: number[]) => void;
    resetEditedRoisForCameraIds: (cameraIds: number[]) => void;
  } | null>(null);
  const referenceGroups = useMemo(() => createReferenceGroups(cameraIds), [cameraIds]);
  const cameraGroups = referenceGroups.map((group) => group.cameraIds);
  const activeReferenceGroup = referenceGroups[activeGroupIndex];
  const activeCameraIds = useMemo(
    () => referenceGroups[activeGroupIndex]?.cameraIds ?? EMPTY_CAMERA_IDS,
    [activeGroupIndex, referenceGroups],
  );
  const referenceFrames0 = useReferenceFrames(referenceGroups[0]?.cameraIds ?? EMPTY_CAMERA_IDS, referenceGroups[0]);
  const referenceFrames1 = useReferenceFrames(referenceGroups[1]?.cameraIds ?? EMPTY_CAMERA_IDS, referenceGroups[1]);
  const referenceFrames2 = useReferenceFrames(referenceGroups[2]?.cameraIds ?? EMPTY_CAMERA_IDS, referenceGroups[2]);
  const referenceFrames3 = useReferenceFrames(referenceGroups[3]?.cameraIds ?? EMPTY_CAMERA_IDS, referenceGroups[3]);
  const referenceFramesByGroup = [referenceFrames0, referenceFrames1, referenceFrames2, referenceFrames3];
  const referenceFrames = referenceFramesByGroup[activeGroupIndex] ?? referenceFrames0;
  const referenceFrameHandlersRef = useRef(referenceFramesByGroup.map((frames) => frames.handlePreviewFrame));
  useEffect(() => {
    referenceFrameHandlersRef.current = [
      referenceFrames0.handlePreviewFrame,
      referenceFrames1.handlePreviewFrame,
      referenceFrames2.handlePreviewFrame,
      referenceFrames3.handlePreviewFrame,
    ];
  }, [
    referenceFrames0.handlePreviewFrame,
    referenceFrames1.handlePreviewFrame,
    referenceFrames2.handlePreviewFrame,
    referenceFrames3.handlePreviewFrame,
  ]);
  const referenceRoi = useReferenceRoi(cameraIds, cameraGroups, activeGroupIndex, initialCameraId, !isNewReferenceMode);
  const referenceFpZones = useReferenceFpZones(cameraGroups, activeGroupIndex, !isNewReferenceMode);
  const {
    captureLatestImages,
    imageUrlsByCameraId,
    loadStoredReferenceImages,
    refreshLatestImages,
  } = referenceFrames;
  const cameraSlots = referenceFrames.cameraSlots.filter((slot) => activeCameraIds.includes(slot.cameraId));
  const submissionCameraIds =
    isNewReferenceMode && replacementCameraIds.length > 0
      ? replacementCameraIds.filter((cameraId) => activeCameraIds.includes(cameraId))
      : activeCameraIds;
  const hasAnyStoredReferenceForActiveGroup =
    Boolean(activeReferenceGroup) &&
    activeCameraIds.some(
      (cameraId) =>
        Boolean(getReferenceImage(cameraId, activeReferenceGroup.phaseId, activeReferenceGroup.groupId)),
    );
  const canSendAllReferences = Boolean(
    submissionCameraIds.length > 0 &&
    submissionCameraIds.every((cameraId) => referenceFrames.framesByCameraId[cameraId]) &&
    referenceRoi.hasRequiredRoisForCameraIds(submissionCameraIds) &&
    status.state === "open",
  );
  useEffect(() => {
    referenceCommitSyncRef.current = {
      cameraIds,
      cameraGroups,
      loadStoredReferenceImages,
      resetEditedFpZonesForCameraIds: referenceFpZones.resetEditedFpZonesForCameraIds,
      resetEditedRoisForCameraIds: referenceRoi.resetEditedRoisForCameraIds,
    };
  });

  useEffect(() => {
    let isActive = true;

    orchestratorApi
      .listCameras()
      .then(({ cameras }) => {
        if (!isActive) {
          return;
        }

        const sortedCameraIds = [...new Set(cameras)].sort((left, right) => left - right);
        setCameraIds(sortedCameraIds);
        const initialGroupIndex = resolveInitialGroupIndex(sortedCameraIds, initialCameraId);
        setActiveGroupIndex(initialGroupIndex);
        setMessage(
          sortedCameraIds.length > 0
            ? `Configured cameras: ${sortedCameraIds.join(", ")}`
            : "No configured cameras found",
        );
      })
      .catch((error) => {
        if (isActive) {
          setMessage(error instanceof Error ? error.message : String(error));
        }
      });

    return () => {
      isActive = false;
    };
  }, [initialCameraId]);

  useEffect(() => {
    const pendingReferenceMessageIds = pendingReferenceMessageIdsRef.current;
    const unsubscribeMessage = orchestratorWs.onMessage((message: ServerWsMessage) => {
      switch (message.type) {
        case "server.hello":
          hasReferenceRef.current = message.payload.session_state !== "NO_REFERENCE";
          enableReferencePreviewImages();
          setMessage("WebSocket connected");
          break;
        case "server.state":
          hasReferenceRef.current = message.payload.session_state !== "NO_REFERENCE";
          enableReferencePreviewImages();
          break;
        case "server.preview_frame":
          // Reference frames must come from a phase-tagged DI3 capture.
          break;
        case "server.preview_batch":
          // Generic preview has no phase/group identity and is unsafe here.
          break;
        case "server.inspect_result": {
          const result = message.payload;
          const targetGroupIndex = resolveReferenceGroupIndex(referenceGroups, result);
          if (targetGroupIndex >= 0) {
            referenceFrameHandlersRef.current[targetGroupIndex]?.(result);
          }
          break;
        }
        case "server.stream_started":
        case "server.stream_stopped":
          // Temporarily disabled: ReferenceSetup does not manage server streams.
          break;
        case "server.reference_bundle_ack": {
          pendingReferenceMessageIdsRef.current.delete(message.message_id);
          const committedCameraIds = pendingReferenceCameraIdsByMessageIdRef.current[message.message_id];
          delete pendingReferenceCameraIdsByMessageIdRef.current[message.message_id];
          if (pendingReferenceMessageIdsRef.current.size === 0) {
            resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
          }
          if (message.payload.ok) {
            setReferenceSubmission((current) =>
              current
                ? {
                    ...current,
                    state: "confirmed",
                  }
                : current,
            );
            hasReferenceRef.current = true;
            setIsNewReferenceMode(false);
            setReplacementCameraIds([]);
            const referenceCommitSync = referenceCommitSyncRef.current;
            if (referenceCommitSync) {
              const targetCameraIds = committedCameraIds ?? referenceCommitSync.cameraIds;
              referenceCommitSync.resetEditedRoisForCameraIds(targetCameraIds);
              referenceCommitSync.resetEditedFpZonesForCameraIds(targetCameraIds);
              referenceCommitSync.loadStoredReferenceImages(targetCameraIds);
            }
            disableReferencePreviewImages();
          }
          if (!message.payload.ok) {
            setReferenceSubmission((current) => (current ? { ...current, state: "rejected" } : current));
          }
          setMessage(
            message.payload.ok
              ? "Эталон подтверждён сервером"
              : "Сервер отклонил эталон: проверьте кадры и ROI контроля",
          );
          break;
        }
        case "server.error":
          for (const messageId of pendingReferenceMessageIdsRef.current) {
            resolveReferenceBundleImages(messageId, false);
          }
          pendingReferenceMessageIdsRef.current.clear();
          resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
          setMessage(`${message.payload.code}: ${message.payload.message}`);
          setReferenceSubmission((current) => (current ? { ...current, state: "rejected" } : current));
          break;
        default:
          break;
      }
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
      pendingReferenceMessageIds.clear();
      pendingReferenceCameraIdsByMessageIdRef.current = {};
      resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      if (hasReferenceRef.current) {
        disableReferencePreviewImages();
      } else {
        enableReferencePreviewImages();
      }
    };
  }, [referenceGroups]);

  useEffect(() => {
    if (status.state === "open") {
      enableReferencePreviewImages();
    }
  }, [status.state]);

  useEffect(() => {
    if (activeCameraIds.length === 0) {
      return;
    }

    let cancelled = false;

    refreshLatestImages().then(({ loadedCameraIds, snapshotCameraIds }) => {
      if (cancelled) {
        return;
      }

      if (loadedCameraIds.length > 0) {
        setMessage(`Live frames loaded for cameras: ${loadedCameraIds.join(", ")}`);
        return;
      }

      if (snapshotCameraIds.length > 0) {
        setMessage(
          `Latest snapshots loaded for cameras: ${snapshotCameraIds.join(", ")}. Waiting for live frames to send reference.`,
        );
      }
    });

    return () => {
      cancelled = true;
    };
  }, [activeCameraIds.length, activeReferenceGroup?.phaseId, refreshLatestImages]);

  useEffect(() => {
    if (status.state !== "open" || activeCameraIds.length === 0 || isReferencePreviewPausedRef.current) {
      return;
    }

    const missingCameraIds = activeCameraIds.filter((cameraId) => !referenceFrames.framesByCameraId[cameraId]);
    if (missingCameraIds.length > 0) {
      return;
    }

    pauseReferencePreview(isReferencePreviewPausedRef);
    window.setTimeout(() => {
      setMessage(`Кадры группы ${activeGroupIndex + 1} получены: камеры ${activeCameraIds.join(", ")}`);
    }, 0);
  }, [activeCameraIds, activeGroupIndex, referenceFrames.framesByCameraId, status.state]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const handleSendAllReferences = () => {
    if (!activeReferenceGroup) {
      setMessage("Список настроенных камер пуст");
      return;
    }
    sendReferenceForGroups([{ ...activeReferenceGroup, cameraIds: submissionCameraIds }]);
  };

  const handleCaptureNewReferenceFrames = async () => {
    if (activeCameraIds.length === 0) {
      setMessage("Список настроенных камер пуст");
      return;
    }

    const targetCameraIds = hasAnyStoredReferenceForActiveGroup
      ? [referenceRoi.selectedCameraId]
      : activeCameraIds;
    const previouslySelectedCameraIds = replacementCameraIds;
    setIsNewReferenceMode(true);
    setMessage(`Capturing latest frames for cameras: ${targetCameraIds.join(", ")}`);
    const { loadedCameraIds, snapshotCameraIds, missingCameraIds } = await captureLatestImages(targetCameraIds);
    const capturedCameraIds = [...loadedCameraIds, ...snapshotCameraIds].sort((left, right) => left - right);

    if (capturedCameraIds.length > 0) {
      setReplacementCameraIds((currentCameraIds) =>
        [...new Set([...currentCameraIds, ...capturedCameraIds])].sort((left, right) => left - right),
      );
      referenceRoi.resetEditedRoisForCameraIds(capturedCameraIds);
      referenceFpZones.resetEditedFpZonesForCameraIds(capturedCameraIds);
    }

    if (capturedCameraIds.length === targetCameraIds.length) {
      const selectedCameraIds = [...new Set([...previouslySelectedCameraIds, ...capturedCameraIds])].sort(
        (left, right) => left - right,
      );
      setMessage(
        hasAnyStoredReferenceForActiveGroup
          ? `New reference frames selected for cameras ${selectedCameraIds.join(", ")}. Other cameras will keep their current references.`
          : `New reference mode: fresh frames captured for cameras ${capturedCameraIds.join(", ")}. Draw ROI contours.`,
      );
      return;
    }

    if (capturedCameraIds.length > 0) {
      setMessage(
        `New reference mode: fresh frames captured for cameras ${capturedCameraIds.join(", ")}. Missing: ${missingCameraIds.join(", ")}`,
      );
      return;
    }

    setIsNewReferenceMode(previouslySelectedCameraIds.length > 0);
    setMessage(`Could not capture latest frames for cameras: ${missingCameraIds.join(", ")}`);
  };

  const handleUseArchivedReference = (archiveId: string) => {
    const archive = getArchivedReferenceGroup(archiveId);
    if (!archive) {
      setMessage("Archived reference was not found");
      return;
    }

    if (status.state !== "open") {
      setMessage("WebSocket is not open");
      return;
    }

    try {
      setIsNewReferenceMode(false);
      startReferenceResumeTimeout(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      const messageId = orchestratorWs.sendReferenceBundle(archive.bundle, archive.imageUrlsByCameraId);
      pendingReferenceCameraIdsByMessageIdRef.current[messageId] = archive.cameraIds;
      stageReferenceBundleContours(
        messageId,
        Object.fromEntries(archive.images.map((image) => [image.cameraId, image.roiPoints])),
        archive.jointCameraId,
        archive.images.find((image) => image.cameraId === archive.jointCameraId)?.jointRoiPoints ?? [],
      );
      pendingReferenceMessageIdsRef.current.add(messageId);
      setReferenceSubmission({
        state: "pending",
        cameraIds: [...archive.cameraIds],
        frameIdsByCameraId: Object.fromEntries(
          archive.images.map((image) => [image.cameraId, String(image.frame.frame_id)]),
        ),
        submittedAtMs: Date.now(),
      });
      setMessage(`Archived reference sent for cameras ${archive.cameraIds.join(", ")}`);
    } catch (error) {
      resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  const sendReferenceForGroups = (targetGroups: ReferenceGroupContext[]) => {
    const groupsToSend = targetGroups.filter((group) => group.cameraIds.length > 0);
    if (groupsToSend.length === 0) {
      setMessage("Список настроенных камер пуст");
      return;
    }

    for (const { cameraIds: groupCameraIds } of groupsToSend) {
      const missingFrameCameraIds = groupCameraIds.filter((cameraId) => !referenceFrames.framesByCameraId[cameraId]);
      if (missingFrameCameraIds.length > 0) {
        setMessage(`Не получены кадры камер: ${missingFrameCameraIds.join(", ")}`);
        return;
      }

      const missingRoiCameraIds = groupCameraIds.filter(
        (cameraId) => (referenceRoi.roiPolygonsByCameraId[cameraId]?.length ?? 0) < 3,
      );
      if (missingRoiCameraIds.length > 0) {
        setMessage(`Не задан ROI контроля для камер: ${missingRoiCameraIds.join(", ")}`);
        return;
      }
    }

    if (status.state !== "open") {
      setMessage("Эталон не отправлен: нет соединения с сервером");
      return;
    }

    try {
      setReferenceSubmission({
        state: "pending",
        cameraIds: groupsToSend.flatMap((group) => group.cameraIds),
        frameIdsByCameraId: Object.fromEntries(
          groupsToSend.flatMap((group) =>
            group.cameraIds.map((cameraId) => [cameraId, String(referenceFrames.framesByCameraId[cameraId]!.frame_id)]),
          ),
        ),
        submittedAtMs: Date.now(),
      });
      startReferenceResumeTimeout(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      pendingReferenceMessageIdsRef.current.clear();
      pendingReferenceCameraIdsByMessageIdRef.current = {};
      for (const { cameraIds: groupCameraIds, phaseId, groupId } of groupsToSend) {
        const payload = createReferenceBundleFromCameraFrames(
          groupCameraIds,
          referenceRoi.getJointCameraIdForCameraIds(groupCameraIds),
          referenceFrames.framesByCameraId,
          referenceRoi.roiPolygonsByCameraId,
          referenceRoi.getJointRoiPolygonForCameraIds(groupCameraIds),
          referenceFpZones.getFpZonesForCameraIds(groupCameraIds),
          phaseId,
          groupId,
        );
        const messageId = orchestratorWs.sendReferenceBundle(payload, imageUrlsByCameraId);
        pendingReferenceCameraIdsByMessageIdRef.current[messageId] = groupCameraIds;
        stageReferenceBundleContours(
          messageId,
          Object.fromEntries(
            groupCameraIds.map((cameraId) => [cameraId, referenceRoi.roiPolygonsByCameraId[cameraId] ?? []]),
          ),
          referenceRoi.getJointCameraIdForCameraIds(groupCameraIds),
          referenceRoi.getJointRoiPolygonForCameraIds(groupCameraIds),
        );
        pendingReferenceMessageIdsRef.current.add(messageId);
      }
      setMessage(
        groupsToSend.length === 1
          ? `Эталон группы ${groupsToSend[0].groupId + 1} отправлен для камер ${groupsToSend[0].cameraIds.join(", ")}`
          : `Reference bundles sent for ${groupsToSend.length} groups`,
      );
      resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
    } catch (error) {
      pendingReferenceMessageIdsRef.current.clear();
      resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      setReferenceSubmission((current) => (current ? { ...current, state: "rejected" } : current));
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  const handleSelectCamera = (cameraId: number) => {
    referenceRoi.setSelectedCameraId(cameraId);

    if (
      isNewReferenceMode &&
      hasAnyStoredReferenceForActiveGroup &&
      !replacementCameraIds.includes(cameraId)
    ) {
      setMessage(`Capturing latest frame for camera ${cameraId}`);
      void captureLatestImages([cameraId]).then(({ loadedCameraIds, snapshotCameraIds }) => {
        const capturedCameraIds = [...loadedCameraIds, ...snapshotCameraIds];
        if (capturedCameraIds.length === 0) {
          setMessage(`Could not capture latest frame for camera ${cameraId}`);
          return;
        }

        setReplacementCameraIds((currentCameraIds) =>
          [...new Set([...currentCameraIds, ...capturedCameraIds])].sort((left, right) => left - right),
        );
        referenceRoi.resetEditedRoisForCameraIds(capturedCameraIds);
        referenceFpZones.resetEditedFpZonesForCameraIds(capturedCameraIds);
        setMessage(
          `Camera ${cameraId} added to the new reference. Cameras without a new frame will keep their current references.`,
        );
      });
      return;
    }

    setMessage(
      referenceFrames.framesByCameraId[cameraId]
        ? `Редактирование ROI для камеры ${cameraId}`
        : `Эталонный кадр для камеры ${cameraId} ещё не пришёл`,
    );
  };

  const handleSelectJointRoi = (cameraId: number) => {
    referenceRoi.selectJointRoi(cameraId);
    setMessage(
      referenceFrames.framesByCameraId[cameraId]
        ? `Редактирование ROI шва этикетки для камеры ${cameraId}`
        : `Эталонный кадр для камеры ${cameraId} ещё не пришёл`,
    );
  };

  return {
    status,
    message,
    cameraIds,
    ...referenceFrames,
    cameraGroups,
    activeReferenceGroup,
    activeGroupIndex,
    setActiveGroupIndex,
    cameraSlots,
    ...referenceRoi,
    ...referenceFpZones,
    canSendAllReferences,
    hasAnyStoredReferenceForActiveGroup,
    isNewReferenceMode,
    replacementCameraIds,
    referenceSubmission,
    handleCaptureNewReferenceFrames,
    handleSendAllReferences,
    handleSelectCamera,
    handleSelectJointRoi,
    handleUseArchivedReference,
  };
}

export function createReferenceGroups(cameraIds: number[]): ReferenceGroupContext[] {
  const cameraGroups: number[][] = [];
  for (let index = 0; index < cameraIds.length; index += CAMERAS_PER_REFERENCE_GROUP) {
    cameraGroups.push(cameraIds.slice(index, index + CAMERAS_PER_REFERENCE_GROUP));
  }
  return [0, 1].flatMap((phaseId) =>
    cameraGroups.slice(0, 2).map((groupCameraIds, cameraSetIndex) => ({
      phaseId,
      groupId: phaseId * 2 + cameraSetIndex,
      cameraIds: groupCameraIds,
    })),
  );
}

export function resolveReferenceGroupIndex(
  referenceGroups: ReferenceGroupContext[],
  result: { camera_id: number; phase_id?: number; group_id?: number },
) {
  const cameraMatches = referenceGroups
    .map((group, index) => ({ group, index }))
    .filter(({ group }) => group.cameraIds.includes(result.camera_id));
  if (cameraMatches.length === 0) {
    return -1;
  }

  const phaseId = result.phase_id ?? 0;
  const groupId = result.group_id;
  if (groupId != null && groupId >= 0) {
    const exact = cameraMatches.find(({ group }) => group.phaseId === phaseId && group.groupId === groupId);
    if (exact) {
      return exact.index;
    }
  }

  const phaseMatch = cameraMatches.find(({ group }) => group.phaseId === phaseId);
  return phaseMatch?.index ?? -1;
}

function resolveInitialGroupIndex(cameraIds: number[], initialCameraId: number | null) {
  if (initialCameraId === null) {
    return 0;
  }
  const sortedCameraIds = [...new Set(cameraIds)].sort((left, right) => left - right);
  const cameraIndex = sortedCameraIds.indexOf(initialCameraId);
  return cameraIndex < 0 ? 0 : Math.floor(cameraIndex / CAMERAS_PER_REFERENCE_GROUP);
}

function pauseReferencePreview(isPausedRef: MutableRefObject<boolean>) {
  if (isPausedRef.current) {
    return;
  }

  orchestratorWs.sendPreviewPause();
  isPausedRef.current = true;
}

function startReferenceResumeTimeout(
  timerRef: MutableRefObject<number | null>,
  isPausedRef: MutableRefObject<boolean>,
) {
  if (timerRef.current !== null) {
    window.clearTimeout(timerRef.current);
  }

  pauseReferencePreview(isPausedRef);
  timerRef.current = window.setTimeout(() => {
    timerRef.current = null;
    resumePreviewAfterReference(timerRef, isPausedRef);
  }, REFERENCE_PREVIEW_PAUSE_TIMEOUT_MS);
}

function resumePreviewAfterReference(
  timerRef: MutableRefObject<number | null>,
  isPausedRef: MutableRefObject<boolean>,
) {
  if (timerRef.current !== null) {
    window.clearTimeout(timerRef.current);
    timerRef.current = null;
  }

  if (!isPausedRef.current) {
    return;
  }

  try {
    orchestratorWs.sendPreviewResume();
    isPausedRef.current = false;
  } catch {
    // The WebSocket status UI will surface connection problems.
  }
}

function enableReferencePreviewImages() {
  try {
    orchestratorWs.enablePreviewImages();
  } catch {
    // The WebSocket status UI will surface connection problems.
  }
}

function disableReferencePreviewImages() {
  try {
    orchestratorWs.disablePreviewImages();
  } catch {
    // The WebSocket status UI will surface connection problems.
  }
}
