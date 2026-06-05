import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { commitReferenceBundleImages } from "../../shared/referenceImages";
import { orchestratorWs } from "../../shared/ws";
import type { ClientReferenceBundlePayload, ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

export function useReferenceSetupController(onClose: () => void, initialJointViewIndex: number | null = null) {
  const status = useSyncExternalStore(
    (onStoreChange) => orchestratorWs.onStatus(onStoreChange),
    () => orchestratorWs.snapshot,
    () => orchestratorWs.snapshot,
  );
  const [message, setMessage] = useState("Waiting for preview frames...");
  const pendingReferenceBundleRef = useRef<{
    messageId: string;
    payload: ClientReferenceBundlePayload;
    imageUrlsByCameraId: Record<number, string>;
  } | null>(null);
  const referenceFrames = useReferenceFrames();
  const referenceRoi = useReferenceRoi(initialJointViewIndex);
  const { handlePreviewFrame, imageUrlsByCameraId, refreshLatestImages } = referenceFrames;
  const canSendReference = Boolean(
    referenceFrames.hasRequiredReferenceFrames && referenceRoi.hasRequiredCameraRois && status.state === "open",
  );

  const handleReferenceBundleAck = useCallback((message: Extract<ServerWsMessage, { type: "server.reference_bundle_ack" }>) => {
    if (pendingReferenceBundleRef.current?.messageId === message.message_id) {
      if (message.payload.ok) {
        commitReferenceBundleImages(
          pendingReferenceBundleRef.current.payload,
          pendingReferenceBundleRef.current.imageUrlsByCameraId,
        );
      }

      pendingReferenceBundleRef.current = null;
    }

    setMessage(message.payload.ok ? "Reference bundle accepted" : "Reference bundle rejected");
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
          handleReferenceBundleAck(message);
          break;
        case "server.error":
          setMessage(`${message.payload.code}: ${message.payload.message}`);
          break;
        default:
          setMessage(`Unknown message: ${message.type}`);
          break;
      }
    });

    orchestratorWs.connect();
    if (orchestratorWs.snapshot.state === "open") {
      try {
        orchestratorWs.sendPreviewPause();
      } catch {
        // best effort; socket can still be reconnecting
      }
    }

    return () => {
      if (orchestratorWs.snapshot.state === "open") {
        try {
          orchestratorWs.sendPreviewResume();
        } catch {
          // best effort on unmount
        }
      }
      unsubscribeMessage();
    };
  }, [handlePreviewFrame, handleReferenceBundleAck]);

  useEffect(() => {
    if (status.state !== "open") {
      return;
    }
    try {
      orchestratorWs.sendPreviewPause();
    } catch {
      // best effort after reconnect
    }
  }, [status.state]);

  useEffect(() => {
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
      pendingReferenceBundleRef.current = null;
    };
  }, [refreshLatestImages]);

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
      setMessage("Reference frames for cameras 0-3 are required");
      return;
    }

    if (!referenceRoi.hasRequiredCameraRois) {
      setMessage("ROI contours for cameras 0-3 are required");
      return;
    }

    try {
      const payload = createReferenceBundleFromCameraFrames(
        referenceFrames.framesByCameraId,
        referenceRoi.jointViewIndex,
        referenceRoi.roiPolygonsByCameraId,
      );
      const messageId = orchestratorWs.sendReferenceBundle(payload);
      pendingReferenceBundleRef.current = {
        messageId,
        payload,
        imageUrlsByCameraId: { ...imageUrlsByCameraId },
      };
      setMessage("Reference bundle sent");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  const handleSelectCamera = async (cameraId: number) => {
    referenceRoi.setSelectedCameraId(cameraId);
    setMessage(`Waiting for live frame from camera ${cameraId}...`);
    const { loadedCameraIds, snapshotCameraIds } = await refreshLatestImages(cameraId);

    if (loadedCameraIds.length > 0) {
      setMessage(`Live frame loaded for camera ${cameraId}`);
      return;
    }

    if (snapshotCameraIds.length > 0) {
      setMessage(`Latest snapshot loaded for camera ${cameraId}. Waiting for live frame to send reference.`);
      return;
    }

    setMessage(`Live frame has not arrived for camera ${cameraId} yet`);
  };

  return {
    status,
    message,
    ...referenceFrames,
    ...referenceRoi,
    canSendReference,
    handleSendReference,
    handleSelectCamera,
  };
}
