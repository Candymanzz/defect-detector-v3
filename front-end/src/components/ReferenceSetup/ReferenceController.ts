import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { MutableRefObject } from "react";
import { orchestratorApi } from "../../shared/api";
import { orchestratorWs } from "../../shared/ws";
import type { ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

const CAMERAS_PER_REFERENCE_GROUP = 5;
const REFERENCE_PREVIEW_PAUSE_TIMEOUT_MS = 15000;

export function useReferenceSetupController(onClose: () => void, initialCameraId: number | null = null) {
  const status = useSyncExternalStore(
    (onStoreChange) => orchestratorWs.onStatus(onStoreChange),
    () => orchestratorWs.snapshot,
    () => orchestratorWs.snapshot,
  );
  const [message, setMessage] = useState("Waiting for preview frames...");
  const [cameraIds, setCameraIds] = useState<number[]>([]);
  const [activeGroupIndex, setActiveGroupIndex] = useState(0);
  const referencePreviewResumeTimerRef = useRef<number | null>(null);
  const isReferencePreviewPausedRef = useRef(false);
  const pendingReferenceMessageIdsRef = useRef<Set<string>>(new Set());
  const cameraGroups = splitCameraGroups(cameraIds);
  const activeCameraIds = cameraGroups[activeGroupIndex] ?? [];
  const referenceFrames = useReferenceFrames(cameraIds);
  const referenceRoi = useReferenceRoi(cameraIds, cameraGroups, activeGroupIndex, initialCameraId);
  const { handlePreviewFrame, imageUrlsByCameraId, refreshLatestImages } = referenceFrames;
  const cameraSlots = referenceFrames.cameraSlots.filter((slot) => activeCameraIds.includes(slot.cameraId));
  const canSendAllReferences = Boolean(
    cameraGroups.length > 0 &&
      cameraGroups.every(
        (groupCameraIds) =>
          groupCameraIds.every((cameraId) => referenceFrames.framesByCameraId[cameraId]) &&
          referenceRoi.hasRequiredRoisForCameraIds(groupCameraIds),
      ) &&
      status.state === "open",
  );

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
          setMessage("WebSocket connected");
          break;
        case "server.state":
          break;
        case "server.preview_frame":
          handlePreviewFrame(message.payload);
          break;
        case "server.stream_started":
        case "server.stream_stopped":
          // Temporarily disabled: ReferenceSetup does not manage server streams.
          break;
        case "server.reference_bundle_ack":
          pendingReferenceMessageIdsRef.current.delete(message.message_id);
          if (pendingReferenceMessageIdsRef.current.size === 0) {
            resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
          }
          setMessage(message.payload.ok ? "Reference bundle accepted" : "Reference bundle rejected");
          break;
        case "server.error":
          pendingReferenceMessageIdsRef.current.clear();
          resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
          setMessage(`${message.payload.code}: ${message.payload.message}`);
          break;
        default:
          break;
      }
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
      pendingReferenceMessageIds.clear();
      resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
    };
  }, [handlePreviewFrame]);

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
        setMessage(`Latest snapshots loaded for cameras: ${snapshotCameraIds.join(", ")}. Waiting for live frames to send reference.`);
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
    sendReferenceForGroups(cameraGroups);
  };

  const sendReferenceForGroups = (targetGroups: number[][]) => {
    const groupsToSend = targetGroups.filter((groupCameraIds) => groupCameraIds.length > 0);
    if (groupsToSend.length === 0) {
      setMessage("Configured camera list is empty");
      return;
    }

    for (const groupCameraIds of groupsToSend) {
      if (!groupCameraIds.every((cameraId) => referenceFrames.framesByCameraId[cameraId])) {
        setMessage(`Reference frames for cameras ${groupCameraIds.join(", ")} are required`);
        return;
      }

      if (!referenceRoi.hasRequiredRoisForCameraIds(groupCameraIds)) {
        setMessage(`ROI contours for cameras ${groupCameraIds.join(", ")} are required`);
        return;
      }
    }

    try {
      startReferenceResumeTimeout(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      pendingReferenceMessageIdsRef.current.clear();
      for (const groupCameraIds of groupsToSend) {
        const payload = createReferenceBundleFromCameraFrames(
          groupCameraIds,
          groupCameraIds[0],
          referenceFrames.framesByCameraId,
          referenceRoi.roiPolygonsByCameraId,
          referenceRoi.getJointRoiPolygonForCameraIds(groupCameraIds),
        );
        const messageId = orchestratorWs.sendReferenceBundle(payload, imageUrlsByCameraId);
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
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  const handleSelectCamera = (cameraId: number) => {
    referenceRoi.setSelectedCameraId(cameraId);
    setMessage(
      referenceFrames.framesByCameraId[cameraId]
        ? `Editing ROI for camera ${cameraId}`
        : `Reference frame has not arrived for camera ${cameraId} yet`,
    );
  };

  const handleSelectJointRoi = () => {
    referenceRoi.selectJointRoi();
    setMessage(
      referenceFrames.framesByCameraId[referenceRoi.jointCameraId]
        ? `Editing joint ROI for camera ${referenceRoi.jointCameraId}`
        : `Reference frame has not arrived for camera ${referenceRoi.jointCameraId} yet`,
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
    canSendAllReferences,
    handleSendAllReferences,
    handleSelectCamera,
    handleSelectJointRoi,
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
