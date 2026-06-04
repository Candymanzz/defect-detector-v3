import { useCallback, useEffect, useRef, useState } from "react";
import { commitReferenceBundleImages } from "../../shared/referenceImages";
import { orchestratorWs } from "../../shared/ws";
import type { ClientReferenceBundlePayload, ServerWsMessage, WsConnectionStatus } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

export function useReferenceSetupController(onClose: () => void, initialJointViewIndex: number | null = null) {
  const [status, setStatus] = useState<WsConnectionStatus>(orchestratorWs.snapshot);
  const [message, setMessage] = useState("Waiting for preview frames...");
  const pendingReferenceBundleRef = useRef<{
    messageId: string;
    payload: ClientReferenceBundlePayload;
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
        commitReferenceBundleImages(pendingReferenceBundleRef.current.payload, imageUrlsByCameraId);
      }

      pendingReferenceBundleRef.current = null;
    }

    setMessage(message.payload.ok ? "Reference bundle accepted" : "Reference bundle rejected");
  }, [imageUrlsByCameraId]);

  useEffect(() => {
    const unsubscribeStatus = orchestratorWs.onStatus(setStatus);
    const unsubscribeMessage = orchestratorWs.onMessage((message: ServerWsMessage) => {
      switch (message.type) {
        case "server.hello":
          setMessage("WebSocket connected");
          break;
        case "server.state":
          setMessage(`State: ${message.payload.session_state} ${message.payload.server_ts_ms}`);
          break;
        case "server.preview_frame":
          handlePreviewFrame(message.payload);
          setMessage(`Camera ${message.payload.camera_id}, frame ${message.payload.frame_id}`);
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

    return () => {
      unsubscribeStatus();
      unsubscribeMessage();
    };
  }, [handlePreviewFrame, handleReferenceBundleAck]);

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
      };
      setMessage("Reference bundle sent");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  const handleSelectCamera = async (cameraId: number) => {
    referenceRoi.setSelectedCameraId(cameraId);
    setMessage(`Loading latest image for camera ${cameraId}...`);
    const ok = await refreshLatestImages(cameraId);
    setMessage(ok ? `Latest image loaded for camera ${cameraId}` : `Latest image is not available for camera ${cameraId}`);
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
