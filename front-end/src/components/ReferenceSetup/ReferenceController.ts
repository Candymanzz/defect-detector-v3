import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { MutableRefObject } from "react";
import { orchestratorApi } from "../../shared/api";
import {
  getArchivedReferenceGroup,
  getReferenceImage,
  stageReferenceArchiveCameraIds,
  stageReferenceBundleContours,
  stageReferenceBundleName,
} from "../../shared/referenceImages";
import { orchestratorWs } from "../../shared/ws";
import type { ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFpZones } from "./useReferenceFpZones";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

const CAMERAS_PER_REFERENCE_GROUP = 5;
const REFERENCE_PREVIEW_PAUSE_TIMEOUT_MS = 15000;

export type ReferenceSubmissionState = {
  state: "pending" | "confirmed" | "rejected";
  cameraIds: number[];
  frameIdsByCameraId: Record<number, string>;
  submittedAtMs: number;
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
  const [referenceName, setReferenceName] = useState("");
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
  const cameraGroups = splitCameraGroups(cameraIds);
  const activeCameraIds = cameraGroups[activeGroupIndex] ?? [];
  const referenceFrames = useReferenceFrames(cameraIds);
  const referenceRoi = useReferenceRoi(cameraIds, cameraGroups, activeGroupIndex, initialCameraId, !isNewReferenceMode);
  const referenceFpZones = useReferenceFpZones(cameraGroups, activeGroupIndex, !isNewReferenceMode);
  const {
    captureLatestImages,
    handlePreviewFrame,
    imageUrlsByCameraId,
    loadStoredReferenceImages,
    refreshLatestImages,
  } = referenceFrames;
  const cameraSlots = referenceFrames.cameraSlots.filter((slot) => activeCameraIds.includes(slot.cameraId));
  const hasAnyStoredReferenceForActiveGroup = activeCameraIds.some((cameraId) => Boolean(getReferenceImage(cameraId)));
  const submissionCameraIds =
    isNewReferenceMode && hasAnyStoredReferenceForActiveGroup
      ? replacementCameraIds
      : activeCameraIds;
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
          handlePreviewFrame(message.payload);
          break;
        case "server.preview_batch":
          for (const previewFrame of message.payload.frames) {
            handlePreviewFrame(previewFrame);
          }
          break;
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
  }, [handlePreviewFrame]);

  useEffect(() => {
    if (status.state === "open") {
      enableReferencePreviewImages();
    }
  }, [status.state]);

  useEffect(() => {
    if (cameraIds.length === 0) {
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
  }, [cameraIds, refreshLatestImages]);

  useEffect(() => {
    if (status.state !== "open" || cameraIds.length === 0 || isReferencePreviewPausedRef.current) {
      return;
    }

    const missingCameraIds = cameraIds.filter((cameraId) => !referenceFrames.framesByCameraId[cameraId]);
    if (missingCameraIds.length > 0) {
      return;
    }

    pauseReferencePreview(isReferencePreviewPausedRef);
    window.setTimeout(() => {
      setMessage(`Reference frames locked for cameras: ${cameraIds.join(", ")}`);
    }, 0);
  }, [cameraIds, referenceFrames.framesByCameraId, status.state]);

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
    sendReferenceForGroups([submissionCameraIds]);
  };

  const handleCaptureNewReferenceFrames = async () => {
    if (activeCameraIds.length === 0) {
      setMessage("Список настроенных камер пуст");
      return;
    }

    if (hasAnyStoredReferenceForActiveGroup && !isNewReferenceMode) {
      setIsNewReferenceMode(true);
      setReplacementCameraIds([]);
      setReferenceName("");
      setMessage("Выберите одну или несколько камер, кадры которых нужно заменить");
      return;
    }

    const targetCameraIds = hasAnyStoredReferenceForActiveGroup ? [referenceRoi.selectedCameraId] : activeCameraIds;
    const previouslySelectedCameraIds = replacementCameraIds;
    setIsNewReferenceMode(true);
    if (!isNewReferenceMode) {
      setReferenceName("");
    }
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

  const handleToggleCameraReplacement = async (cameraId: number) => {
    referenceRoi.setSelectedCameraId(cameraId);
    if (!isNewReferenceMode) {
      setIsNewReferenceMode(true);
      setReferenceName("");
    }

    if (replacementCameraIds.includes(cameraId)) {
      setReplacementCameraIds((currentCameraIds) => currentCameraIds.filter((candidate) => candidate !== cameraId));
      referenceRoi.resetEditedRoisForCameraIds([cameraId]);
      referenceFpZones.resetEditedFpZonesForCameraIds([cameraId]);
      loadStoredReferenceImages([cameraId]);
      setMessage(`Камера ${cameraId} не будет изменена — останется действующий эталон`);
      return;
    }

    setMessage(`Получение нового кадра камеры ${cameraId}...`);
    const { loadedCameraIds, snapshotCameraIds } = await captureLatestImages([cameraId]);
    const capturedCameraIds = [...loadedCameraIds, ...snapshotCameraIds];
    if (capturedCameraIds.length === 0) {
      setMessage(`Не удалось получить новый кадр камеры ${cameraId}`);
      return;
    }

    setReplacementCameraIds((currentCameraIds) =>
      [...new Set([...currentCameraIds, cameraId])].sort((left, right) => left - right),
    );
    referenceRoi.resetEditedRoisForCameraIds([cameraId]);
    referenceFpZones.resetEditedFpZonesForCameraIds([cameraId]);
    setMessage(`Камера ${cameraId} выбрана для замены. Можно выбрать другие камеры или подтвердить изменения.`);
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
      stageReferenceBundleName(messageId, archive.name ?? "");
      stageReferenceArchiveCameraIds(messageId, archive.cameraIds);
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

  const sendReferenceForGroups = (targetGroups: number[][]) => {
    const groupsToSend = targetGroups.filter((groupCameraIds) => groupCameraIds.length > 0);
    if (groupsToSend.length === 0) {
      setMessage("Список настроенных камер пуст");
      return;
    }

    for (const groupCameraIds of groupsToSend) {
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
        cameraIds: groupsToSend.flatMap((groupCameraIds) => groupCameraIds),
        frameIdsByCameraId: Object.fromEntries(
          groupsToSend.flatMap((groupCameraIds) =>
            groupCameraIds.map((cameraId) => [cameraId, String(referenceFrames.framesByCameraId[cameraId]!.frame_id)]),
          ),
        ),
        submittedAtMs: Date.now(),
      });
      startReferenceResumeTimeout(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      pendingReferenceMessageIdsRef.current.clear();
      pendingReferenceCameraIdsByMessageIdRef.current = {};
      for (const groupCameraIds of groupsToSend) {
        const payload = createReferenceBundleFromCameraFrames(
          groupCameraIds,
          referenceRoi.getJointCameraIdForCameraIds(groupCameraIds),
          referenceFrames.framesByCameraId,
          referenceRoi.roiPolygonsByCameraId,
          referenceRoi.getJointRoiPolygonForCameraIds(groupCameraIds),
          referenceFpZones.getFpZonesForCameraIds(groupCameraIds),
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
        stageReferenceBundleName(messageId, referenceName);
        stageReferenceArchiveCameraIds(messageId, activeCameraIds);
        pendingReferenceMessageIdsRef.current.add(messageId);
      }
      setMessage(
        groupsToSend.length === 1
          ? `Reference bundle sent for cameras ${groupsToSend[0].join(", ")}`
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
    activeGroupIndex,
    setActiveGroupIndex,
    cameraSlots,
    ...referenceRoi,
    ...referenceFpZones,
    canSendAllReferences,
    hasAnyStoredReferenceForActiveGroup,
    isNewReferenceMode,
    replacementCameraIds,
    referenceName,
    setReferenceName,
    referenceSubmission,
    handleCaptureNewReferenceFrames,
    handleToggleCameraReplacement,
    handleSendAllReferences,
    handleSelectCamera,
    handleSelectJointRoi,
    handleUseArchivedReference,
  };
}

function splitCameraGroups(cameraIds: number[]) {
  const groups: number[][] = [];
  for (let index = 0; index < cameraIds.length; index += CAMERAS_PER_REFERENCE_GROUP) {
    groups.push(cameraIds.slice(index, index + CAMERAS_PER_REFERENCE_GROUP));
  }
  return groups;
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
