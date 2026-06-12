import { useEffect, useState, useSyncExternalStore } from "react";
import { orchestratorApi } from "../../shared/api";
import { orchestratorWs } from "../../shared/ws";
import type { ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

export function useReferenceSetupController(onClose: () => void, initialCameraId: number | null = null) {
  const status = useSyncExternalStore(
    (onStoreChange) => orchestratorWs.onStatus(onStoreChange),
    () => orchestratorWs.snapshot,
    () => orchestratorWs.snapshot,
  );
  const [message, setMessage] = useState("Waiting for preview frames...");
  const [cameraIds, setCameraIds] = useState<number[]>([]);
  const referenceFrames = useReferenceFrames(cameraIds);
  const referenceRoi = useReferenceRoi(cameraIds, initialCameraId);
  const { handlePreviewFrame, imageUrlsByCameraId, refreshLatestImages } = referenceFrames;
  const canSendReference = Boolean(
    cameraIds.length > 0 &&
      referenceFrames.hasRequiredReferenceFrames &&
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

        setCameraIds(cameras);
        setMessage(
          cameras.length > 0
            ? `Configured cameras: ${cameras.join(", ")}`
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
    if (!referenceFrames.hasRequiredReferenceFrames) {
      setMessage(`Reference frames for cameras ${cameraIds.join(", ")} are required`);
      return;
    }

    if (!referenceRoi.hasRequiredCameraRois) {
      setMessage(`ROI contours for cameras ${cameraIds.join(", ")} are required`);
      return;
    }

    if (!referenceRoi.hasRequiredJointRoi) {
      setMessage(`Joint ROI contour for camera ${referenceRoi.jointCameraId} is required`);
      return;
    }

    try {
      const payload = createReferenceBundleFromCameraFrames(
        cameraIds,
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
    ...referenceRoi,
    canSendReference,
    handleSendReference,
    handleSelectCamera,
    handleSelectJointRoi,
  };
}
