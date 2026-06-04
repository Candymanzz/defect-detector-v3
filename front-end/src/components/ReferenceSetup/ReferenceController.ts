import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { commitReferenceBundleImages } from "../../shared/referenceImages";
import { orchestratorWs } from "../../shared/ws";
import type { ClientReferenceBundlePayload, PreviewFramePayload, ServerWsMessage } from "../../shared/ws";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";
import { REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";
import { useReferenceFrames } from "./useReferenceFrames";
import { useReferenceRoi } from "./useReferenceRoi";

const STREAM_START_RETRY_MS = 3000;

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
  const statusRef = useRef(status);
  const referenceFramesByCameraIdRef = useRef<Record<number, PreviewFramePayload>>({});
  const pendingStreamCameraIdsRef = useRef<Set<number>>(new Set());
  const activeStreamCameraIdsRef = useRef<Set<number>>(new Set());
  const stoppingStreamCameraIdsRef = useRef<Set<number>>(new Set());
  const streamRetryTimeoutIdsRef = useRef<Map<number, number>>(new Map());
  const requestStreamStartRef = useRef<(cameraId: number) => void>(() => {});
  const canSendReference = Boolean(
    referenceFrames.hasRequiredReferenceFrames && referenceRoi.hasRequiredCameraRois && status.state === "open",
  );

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  useEffect(() => {
    referenceFramesByCameraIdRef.current = referenceFrames.framesByCameraId;
  }, [referenceFrames.framesByCameraId]);

  const clearStreamRetryTimer = useCallback((cameraId: number) => {
    const timeoutId = streamRetryTimeoutIdsRef.current.get(cameraId);

    if (timeoutId !== undefined) {
      window.clearTimeout(timeoutId);
      streamRetryTimeoutIdsRef.current.delete(cameraId);
    }
  }, []);

  const stopManagedStream = useCallback((cameraId: number) => {
    if (!activeStreamCameraIdsRef.current.has(cameraId) || stoppingStreamCameraIdsRef.current.has(cameraId)) {
      return;
    }

    clearStreamRetryTimer(cameraId);
    activeStreamCameraIdsRef.current.delete(cameraId);
    pendingStreamCameraIdsRef.current.delete(cameraId);
    stoppingStreamCameraIdsRef.current.add(cameraId);

    try {
      orchestratorWs.sendStreamStop({ camera_id: cameraId });
    } catch {
      stoppingStreamCameraIdsRef.current.delete(cameraId);
    }
  }, [clearStreamRetryTimer]);

  const requestStreamStart = useCallback((cameraId: number) => {
    if (statusRef.current.state !== "open") {
      return;
    }

    if (
      referenceFramesByCameraIdRef.current[cameraId] ||
      pendingStreamCameraIdsRef.current.has(cameraId) ||
      activeStreamCameraIdsRef.current.has(cameraId) ||
      stoppingStreamCameraIdsRef.current.has(cameraId)
    ) {
      return;
    }

    try {
      orchestratorWs.sendStreamStart({
        camera_id: cameraId,
        max_fps: 1,
      });
      pendingStreamCameraIdsRef.current.add(cameraId);
      clearStreamRetryTimer(cameraId);

      const timeoutId = window.setTimeout(() => {
        streamRetryTimeoutIdsRef.current.delete(cameraId);

        if (!pendingStreamCameraIdsRef.current.has(cameraId)) {
          return;
        }

        pendingStreamCameraIdsRef.current.delete(cameraId);

        if (!referenceFramesByCameraIdRef.current[cameraId] && statusRef.current.state === "open") {
          requestStreamStartRef.current(cameraId);
        }
      }, STREAM_START_RETRY_MS);

      streamRetryTimeoutIdsRef.current.set(cameraId, timeoutId);
    } catch {
      pendingStreamCameraIdsRef.current.delete(cameraId);
    }
  }, [clearStreamRetryTimer]);

  useEffect(() => {
    requestStreamStartRef.current = requestStreamStart;
  }, [requestStreamStart]);

  const cleanupManagedStreams = useCallback(() => {
    streamRetryTimeoutIdsRef.current.forEach((timeoutId) => window.clearTimeout(timeoutId));
    streamRetryTimeoutIdsRef.current.clear();

    for (const cameraId of activeStreamCameraIdsRef.current) {
      try {
        orchestratorWs.sendStreamStop({ camera_id: cameraId });
      } catch {
        // The socket may already be closed while the modal is unmounting.
      }
    }

    activeStreamCameraIdsRef.current.clear();
    pendingStreamCameraIdsRef.current.clear();
    stoppingStreamCameraIdsRef.current.clear();
  }, []);

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
          if (REFERENCE_REQUIRED_CAMERA_IDS.includes(message.payload.camera_id as (typeof REFERENCE_REQUIRED_CAMERA_IDS)[number])) {
            clearStreamRetryTimer(message.payload.camera_id);
            pendingStreamCameraIdsRef.current.delete(message.payload.camera_id);

            if (activeStreamCameraIdsRef.current.has(message.payload.camera_id)) {
              stopManagedStream(message.payload.camera_id);
            }
          }
          break;
        case "server.stream_started":
          if (REFERENCE_REQUIRED_CAMERA_IDS.includes(message.payload.camera_id as (typeof REFERENCE_REQUIRED_CAMERA_IDS)[number])) {
            clearStreamRetryTimer(message.payload.camera_id);

            if (pendingStreamCameraIdsRef.current.has(message.payload.camera_id)) {
              pendingStreamCameraIdsRef.current.delete(message.payload.camera_id);
              activeStreamCameraIdsRef.current.add(message.payload.camera_id);

              if (referenceFramesByCameraIdRef.current[message.payload.camera_id]) {
                stopManagedStream(message.payload.camera_id);
              }
            }
          }
          break;
        case "server.stream_stopped":
          clearStreamRetryTimer(message.payload.camera_id);
          pendingStreamCameraIdsRef.current.delete(message.payload.camera_id);
          activeStreamCameraIdsRef.current.delete(message.payload.camera_id);
          stoppingStreamCameraIdsRef.current.delete(message.payload.camera_id);
          break;
        case "server.reference_bundle_ack":
          handleReferenceBundleAck(message);
          break;
        case "server.error":
          pendingReferenceBundleRef.current = null;
          setMessage(`${message.payload.code}: ${message.payload.message}`);
          break;
        default:
          setMessage(`Unknown message: ${message.type}`);
          break;
      }
    });

    orchestratorWs.connect();

    return () => {
      unsubscribeMessage();
    };
  }, [clearStreamRetryTimer, handlePreviewFrame, handleReferenceBundleAck, stopManagedStream]);

  useEffect(() => {
    if (status.state !== "open") {
      cleanupManagedStreams();
      return;
    }

    for (const cameraId of REFERENCE_REQUIRED_CAMERA_IDS) {
      if (!referenceFrames.framesByCameraId[cameraId]) {
        requestStreamStart(cameraId);
      }
    }
  }, [cleanupManagedStreams, referenceFrames.framesByCameraId, requestStreamStart, status.state]);

  useEffect(() => {
    return cleanupManagedStreams;
  }, [cleanupManagedStreams]);

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
    const { loadedCameraIds } = await refreshLatestImages(cameraId);
    setMessage(
      loadedCameraIds.length > 0
        ? `Latest image loaded for camera ${cameraId}`
        : `Live frame has not arrived for camera ${cameraId} yet`,
    );
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
