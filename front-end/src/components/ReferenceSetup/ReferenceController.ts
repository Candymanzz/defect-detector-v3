import { useEffect, useState, useSyncExternalStore } from "react";
import { orchestratorApi } from "../../shared/api";
import { orchestratorWs } from "../../shared/ws";
import type { ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

const CAMERAS_PER_REFERENCE_GROUP = 5;

export function useReferenceSetupController(onClose: () => void, initialCameraId: number | null = null) {
  const status = useSyncExternalStore(
    (onStoreChange) => orchestratorWs.onStatus(onStoreChange),
    () => orchestratorWs.snapshot,
    () => orchestratorWs.snapshot,
  );
  const [message, setMessage] = useState("Waiting for preview frames...");
  const [cameraIds, setCameraIds] = useState<number[]>([]);
  const [activeGroupIndex, setActiveGroupIndex] = useState(0);
  const cameraGroups = splitCameraGroups(cameraIds);
  const activeCameraIds = cameraGroups[activeGroupIndex] ?? [];
  const referenceFrames = useReferenceFrames(cameraIds);
  const referenceRoi = useReferenceRoi(cameraIds, cameraGroups, activeGroupIndex, initialCameraId);
  const { handlePreviewFrame, imageUrlsByCameraId, refreshLatestImages } = referenceFrames;
  const cameraSlots = referenceFrames.cameraSlots.filter((slot) => activeCameraIds.includes(slot.cameraId));
  const canSendReference = Boolean(
    activeCameraIds.length > 0 &&
      activeCameraIds.every((cameraId) => referenceFrames.framesByCameraId[cameraId]) &&
      referenceRoi.hasRequiredCameraRois &&
      referenceRoi.hasRequiredJointRoi &&
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
  }, []);

  useEffect(() => {
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
          setMessage(message.payload.ok ? "Reference bundle accepted" : "Reference bundle rejected");
          break;
        case "server.error":
          setMessage(`${message.payload.code}: ${message.payload.message}`);
          break;
        default:
          break;
      }
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
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
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const handleSendReference = () => {
    if (!activeCameraIds.every((cameraId) => referenceFrames.framesByCameraId[cameraId])) {
      setMessage(`Reference frames for cameras ${activeCameraIds.join(", ")} are required`);
      return;
    }

    if (!referenceRoi.hasRequiredCameraRois) {
      setMessage(`ROI contours for cameras ${activeCameraIds.join(", ")} are required`);
      return;
    }

    if (!referenceRoi.hasRequiredJointRoi) {
      setMessage(`Joint ROI contour for camera ${referenceRoi.jointCameraId} is required`);
      return;
    }

    try {
      const payload = createReferenceBundleFromCameraFrames(
        activeCameraIds,
        referenceRoi.jointCameraId,
        referenceFrames.framesByCameraId,
        referenceRoi.roiPolygonsByCameraId,
        referenceRoi.jointRoiPolygon,
      );
      orchestratorWs.sendReferenceBundle(payload, imageUrlsByCameraId);
      setMessage("Reference bundle sent");
    } catch (error) {
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
    canSendReference,
    handleSendReference,
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
