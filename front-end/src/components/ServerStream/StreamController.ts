import { useCallback, useEffect, useRef, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { orchestratorWs } from "../../shared/ws";
import type { WsConnectionStatus } from "../../shared/ws";

export type StreamState = "idle" | "starting" | "playing" | "stopping" | "error";

type UseStreamControllerOptions = {
  cameraId: number;
  enabled: boolean;
  autoStart?: boolean;
  maxFps?: number;
};

const DEFAULT_MAX_FPS = 20;
const FIRST_FRAME_TIMEOUT_MS = 5000;
const STOP_ACK_TIMEOUT_MS = 5000;

export function useStreamController({
  cameraId,
  enabled,
  autoStart = true,
  maxFps = DEFAULT_MAX_FPS,
}: UseStreamControllerOptions) {
  const [streamState, setStreamState] = useState<StreamState>("idle");
  const [message, setMessage] = useState("Stream stopped");
  const [mjpegUrl, setMjpegUrl] = useState<string>();
  const [status, setStatus] = useState<WsConnectionStatus>(orchestratorWs.snapshot);
  const streamStateRef = useRef(streamState);
  const autoStartAttemptedRef = useRef(false);
  const firstFrameTimerRef = useRef<number | null>(null);
  const stopAckTimerRef = useRef<number | null>(null);
  const stopAckResolverRef = useRef<((stopped: boolean) => void) | null>(null);

  useEffect(() => {
    streamStateRef.current = streamState;
  }, [streamState]);

  const clearFirstFrameTimer = useCallback(() => {
    if (firstFrameTimerRef.current !== null) {
      window.clearTimeout(firstFrameTimerRef.current);
      firstFrameTimerRef.current = null;
    }
  }, []);

  const resolveStopAck = useCallback((stopped: boolean) => {
    if (stopAckTimerRef.current !== null) {
      window.clearTimeout(stopAckTimerRef.current);
      stopAckTimerRef.current = null;
    }
    stopAckResolverRef.current?.(stopped);
    stopAckResolverRef.current = null;
  }, []);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    orchestratorWs.connect();

    const unsubscribeStatus = orchestratorWs.onStatus(setStatus);
    const unsubscribeMessage = orchestratorWs.onMessage((wsMessage) => {
      switch (wsMessage.type) {
        case "server.stream_started": {
          if (wsMessage.payload.camera_id !== cameraId) {
            return;
          }

          const streamPath = wsMessage.payload.mjpeg_path;

          setMjpegUrl(streamPath ? orchestratorApi.url(streamPath) : orchestratorApi.streamMjpegUrl(cameraId));
          setStreamState("playing");
          setMessage(`Stream started: ${wsMessage.payload.max_fps} FPS`);
          clearFirstFrameTimer();
          firstFrameTimerRef.current = window.setTimeout(() => {
            if (streamStateRef.current === "playing") {
              setMjpegUrl(undefined);
              setStreamState("error");
              setMessage("Stream started, but no camera frames were received");
            }
          }, FIRST_FRAME_TIMEOUT_MS);
          return;
        }

        case "server.preview_frame":
          if (wsMessage.payload.camera_id !== cameraId || streamStateRef.current !== "playing") {
            return;
          }

          clearFirstFrameTimer();
          return;

        case "server.stream_stopped":
          if (wsMessage.payload.camera_id !== cameraId) {
            return;
          }

          clearFirstFrameTimer();
          setMjpegUrl(undefined);
          setStreamState("idle");
          setMessage("Stream stopped");
          resolveStopAck(true);
          return;

        case "server.error":
          if (streamStateRef.current !== "starting" && streamStateRef.current !== "stopping") {
            return;
          }

          clearFirstFrameTimer();
          setMjpegUrl(undefined);
          setStreamState("error");
          setMessage(`${wsMessage.payload.code}: ${wsMessage.payload.message}`);
          resolveStopAck(false);
          return;

        default:
          return;
      }
    });

    return () => {
      unsubscribeStatus();
      unsubscribeMessage();

      if (streamStateRef.current === "starting" || streamStateRef.current === "playing") {
        try {
          orchestratorWs.sendStreamStop({ camera_id: cameraId });
        } catch {
          // The socket may already be closed while the modal is unmounting.
        }
      }
      clearFirstFrameTimer();
      resolveStopAck(false);
    };
  }, [cameraId, clearFirstFrameTimer, enabled, resolveStopAck]);

  const startStream = useCallback(() => {
    if (!orchestratorWs.isOpen) {
      setStreamState("error");
      setMessage("WebSocket is not open yet");
      return;
    }

    try {
      clearFirstFrameTimer();
      setMjpegUrl(undefined);
      setStreamState("starting");
      setMessage("Starting stream...");
      orchestratorWs.sendStreamStart({
        camera_id: cameraId,
        max_fps: maxFps,
      });
    } catch (error) {
      setStreamState("error");
      setMessage(errorMessage(error));
    }
  }, [cameraId, clearFirstFrameTimer, maxFps]);

  const handleStreamImageError = useCallback(() => {
    clearFirstFrameTimer();
    setMjpegUrl(undefined);
    setStreamState("error");
    setMessage("MJPEG stream image failed to load");
  }, [clearFirstFrameTimer]);

  const stopStream = useCallback(async () => {
    if (!orchestratorWs.isOpen) {
      setStreamState("error");
      setMessage("WebSocket is not open yet");
      return false;
    }

    if (streamStateRef.current === "idle") {
      return true;
    }

    try {
      clearFirstFrameTimer();
      setStreamState("stopping");
      setMessage("Stopping stream...");
      const stopAck = new Promise<boolean>((resolve) => {
        resolveStopAck(false);
        stopAckResolverRef.current = resolve;
        stopAckTimerRef.current = window.setTimeout(() => {
          stopAckTimerRef.current = null;
          stopAckResolverRef.current = null;
          setStreamState("error");
          setMessage("Timed out while stopping the previous stream");
          resolve(false);
        }, STOP_ACK_TIMEOUT_MS);
      });
      orchestratorWs.sendStreamStop({
        camera_id: cameraId,
      });
      return await stopAck;
    } catch (error) {
      resolveStopAck(false);
      setStreamState("error");
      setMessage(errorMessage(error));
      return false;
    }
  }, [cameraId, clearFirstFrameTimer, resolveStopAck]);

  const isPlaying = streamState === "playing";
  const isBusy = streamState === "starting" || streamState === "stopping";
  const isSocketOpen = status.state === "open";

  useEffect(() => {
    if (!autoStart || !enabled || !isSocketOpen || autoStartAttemptedRef.current || streamState !== "idle") {
      return;
    }

    autoStartAttemptedRef.current = true;
    startStream();
  }, [autoStart, enabled, isSocketOpen, startStream, streamState]);

  const prepareCameraSwitch = useCallback(() => {
    autoStartAttemptedRef.current = false;
  }, []);

  return {
    status,
    streamState,
    message,
    mjpegUrl,
    isPlaying,
    isBusy,
    canStart: enabled && isSocketOpen && !isPlaying && !isBusy,
    canStop: enabled && isSocketOpen && (isPlaying || streamState === "starting"),
    startStream,
    stopStream,
    prepareCameraSwitch,
    handleStreamImageError,
  };
}
