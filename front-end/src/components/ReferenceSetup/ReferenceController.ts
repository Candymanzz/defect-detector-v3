import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { MutableRefObject } from "react";
import { orchestratorApi } from "../../shared/api";
import {
  getArchivedReferenceGroup,
  getReferenceImage,
  stageReferenceBundleContours,
  updateReferenceFpZones,
} from "../../shared/referenceImages";
import { orchestratorWs } from "../../shared/ws";
import type { ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFpZones } from "./useReferenceFpZones";
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
  const hasReferenceRef = useRef(false);
  const pendingReferenceMessageIdsRef = useRef<Set<string>>(new Set());
  const pendingFpZonesRef = useRef<{ cameraIds: number[]; zones: ReturnType<typeof referenceFpZonesCopy> } | null>(null);
  const loadedFpZoneKeysRef = useRef<Set<string>>(new Set());
  const referenceCommitSyncRef = useRef<{
    cameraIds: number[];
    cameraGroups: number[][];
    loadStoredReferenceImages: () => { loadedCameraIds: number[]; missingCameraIds: number[] };
    resetEditedFpZonesForCameraIds: (cameraIds: number[]) => void;
    resetEditedRoisForCameraIds: (cameraIds: number[]) => void;
  } | null>(null);
  const cameraGroups = splitCameraGroups(cameraIds);
  const activeCameraIds = cameraGroups[activeGroupIndex] ?? [];
  const referenceFrames = useReferenceFrames(cameraIds);
  const referenceRoi = useReferenceRoi(cameraIds, cameraGroups, activeGroupIndex, initialCameraId);
  const referenceFpZones = useReferenceFpZones(cameraGroups, activeGroupIndex);
  const setReferenceFpZones = referenceFpZones.setFpZones;
  const {
    captureLatestImages,
    handlePreviewFrame,
    imageUrlsByCameraId,
    loadStoredReferenceImages,
    refreshLatestImages,
  } = referenceFrames;
  const cameraSlots = referenceFrames.cameraSlots.filter((slot) => activeCameraIds.includes(slot.cameraId));
  const hasStoredReferenceForActiveGroup = activeCameraIds.some((cameraId) => Boolean(getReferenceImage(cameraId)));
  const canSendAllReferences = Boolean(
    cameraGroups.length > 0 &&
      cameraGroups.every(
        (groupCameraIds) =>
          groupCameraIds.every((cameraId) => referenceFrames.framesByCameraId[cameraId]) &&
          referenceRoi.hasRequiredRoisForCameraIds(groupCameraIds) &&
          referenceFpZones.hasValidFpZonesForCameraIds(groupCameraIds),
      ) &&
      status.state === "open",
  );
  const activeJointCameraId = referenceRoi.getJointCameraIdForCameraIds(activeCameraIds);
  const activeCameraKey = activeCameraIds.join(",");
  const activeProductType =
    referenceFrames.framesByCameraId[activeJointCameraId]?.detector.product_type;
  const canSaveFpZones = Boolean(
    activeCameraIds.length > 0 &&
      referenceFrames.framesByCameraId[activeJointCameraId] &&
      referenceFpZones.hasValidFpZonesForCameraIds(activeCameraIds) &&
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
        case "server.reference_bundle_ack":
          pendingReferenceMessageIdsRef.current.delete(message.message_id);
          if (pendingReferenceMessageIdsRef.current.size === 0) {
            resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
          }
          if (message.payload.ok) {
            hasReferenceRef.current = true;
            const referenceCommitSync = referenceCommitSyncRef.current;
            if (referenceCommitSync) {
              referenceCommitSync.resetEditedRoisForCameraIds(referenceCommitSync.cameraIds);
              for (const groupCameraIds of referenceCommitSync.cameraGroups) {
                referenceCommitSync.resetEditedFpZonesForCameraIds(groupCameraIds);
              }
              referenceCommitSync.loadStoredReferenceImages();
            }
            disableReferencePreviewImages();
          }
          setMessage(message.payload.ok ? "Reference bundle accepted" : "Reference bundle rejected");
          break;
        case "server.fp_zones_ack":
          if (message.payload.ok && pendingFpZonesRef.current) {
            updateReferenceFpZones(
              pendingFpZonesRef.current.cameraIds,
              pendingFpZonesRef.current.zones,
            );
          }
          pendingFpZonesRef.current = null;
          setMessage(message.payload.ok ? "FP zones saved successfully" : "FP zones were not saved");
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
        setMessage(`Latest snapshots loaded for cameras: ${snapshotCameraIds.join(", ")}. Waiting for live frames to send reference.`);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [cameraIds, refreshLatestImages]);

  useEffect(() => {
    if (!activeCameraKey || !activeProductType) return;

    const loadKey = `${activeCameraKey}:${activeProductType}`;
    if (loadedFpZoneKeysRef.current.has(loadKey)) return;
    loadedFpZoneKeysRef.current.add(loadKey);

    let cancelled = false;
    orchestratorApi
      .getFpZones(activeProductType)
      .then(({ zones }) => {
        if (cancelled) return;
        setReferenceFpZones(
          zones.map((zone) => ({
            id: zone.id,
            note: zone.note ?? "",
            points_norm_heatmap: zone.points_norm_heatmap,
          })),
        );
      })
      .catch(() => {
        loadedFpZoneKeysRef.current.delete(loadKey);
      });

    return () => {
      cancelled = true;
    };
  }, [activeCameraKey, activeProductType, setReferenceFpZones]);

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

  const handleCaptureNewReferenceFrames = async () => {
    if (activeCameraIds.length === 0) {
      setMessage("Configured camera list is empty");
      return;
    }

    setMessage(`Capturing latest frames for cameras: ${activeCameraIds.join(", ")}`);
    const { loadedCameraIds, snapshotCameraIds, missingCameraIds } = await captureLatestImages(activeCameraIds);
    const capturedCameraIds = [...loadedCameraIds, ...snapshotCameraIds].sort((left, right) => left - right);

    if (capturedCameraIds.length === activeCameraIds.length) {
      setMessage(`New reference frames captured for cameras: ${capturedCameraIds.join(", ")}`);
      return;
    }

    if (capturedCameraIds.length > 0) {
      setMessage(
        `New reference frames captured for cameras: ${capturedCameraIds.join(", ")}. Missing: ${missingCameraIds.join(", ")}`,
      );
      return;
    }

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
      startReferenceResumeTimeout(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      const messageId = orchestratorWs.sendReferenceBundle(archive.bundle, archive.imageUrlsByCameraId);
      stageReferenceBundleContours(
        messageId,
        Object.fromEntries(archive.images.map((image) => [image.cameraId, image.roiPoints])),
        archive.jointCameraId,
        archive.images.find((image) => image.cameraId === archive.jointCameraId)?.jointRoiPoints ?? [],
      );
      pendingReferenceMessageIdsRef.current.add(messageId);
      setMessage(`Archived reference sent for cameras ${archive.cameraIds.join(", ")}`);
    } catch (error) {
      resumePreviewAfterReference(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  const handleSaveFpZones = () => {
    if (!canSaveFpZones) {
      setMessage("A frame and valid FP zone contours are required");
      return;
    }

    const jointFrame = referenceFrames.framesByCameraId[activeJointCameraId];
    if (!jointFrame) {
      setMessage(`Reference frame for camera ${activeJointCameraId} is missing`);
      return;
    }

    try {
      const zones = referenceFpZones.getFpZonesForCameraIds(activeCameraIds);
      orchestratorWs.sendFpZonesUpdate({
        heatmap_width: jointFrame.current.width,
        heatmap_height: jointFrame.current.height,
        fp_zones: zones,
      });
      pendingFpZonesRef.current = {
        cameraIds: [...activeCameraIds],
        zones: referenceFpZonesCopy(zones),
      };
      setMessage("FP zones update sent");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
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

      if (!referenceFpZones.hasValidFpZonesForCameraIds(groupCameraIds)) {
        setMessage(`Each FP zone for cameras ${groupCameraIds.join(", ")} requires at least 3 points`);
        return;
      }
    }

    try {
      startReferenceResumeTimeout(referencePreviewResumeTimerRef, isReferencePreviewPausedRef);
      pendingReferenceMessageIdsRef.current.clear();
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
        stageReferenceBundleContours(
          messageId,
          Object.fromEntries(
            groupCameraIds.map((cameraId) => [
              cameraId,
              referenceRoi.roiPolygonsByCameraId[cameraId] ?? [],
            ]),
          ),
          referenceRoi.getJointCameraIdForCameraIds(groupCameraIds),
          referenceRoi.getJointRoiPolygonForCameraIds(groupCameraIds),
        );
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

  const handleSelectJointRoi = (cameraId: number) => {
    referenceRoi.selectJointRoi(cameraId);
    setMessage(
      referenceFrames.framesByCameraId[cameraId]
        ? `Editing optional joint ROI for camera ${cameraId}`
        : `Reference frame has not arrived for camera ${cameraId} yet`,
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
    canSaveFpZones,
    hasStoredReferenceForActiveGroup,
    handleCaptureNewReferenceFrames,
    handleSendAllReferences,
    handleSaveFpZones,
    handleSelectCamera,
    handleSelectJointRoi,
    handleUseArchivedReference,
  };
}

function referenceFpZonesCopy<T extends { id?: string; note: string; points_norm_heatmap: { x: number; y: number }[] }>(
  zones: T[],
) {
  return zones.map((zone) => ({
    ...zone,
    points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ ...point })),
  }));
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
