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

  useEffect(() => {
    streamStateRef.current = streamState;
  }, [streamState]);

  const clearFirstFrameTimer = useCallback(() => {
    if (firstFrameTimerRef.current !== null) {
      window.clearTimeout(firstFrameTimerRef.current);
      firstFrameTimerRef.current = null;
    }
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
          setMessage(`Stream active, frame ${wsMessage.payload.frame_id}`);
          return;

        case "server.stream_stopped":
          if (wsMessage.payload.camera_id !== cameraId) {
            return;
          }

          clearFirstFrameTimer();
          setMjpegUrl(undefined);
          setStreamState("idle");
          setMessage("Stream stopped");
          return;

        case "server.error":
          if (streamStateRef.current !== "starting" && streamStateRef.current !== "stopping") {
            return;
          }

          clearFirstFrameTimer();
          setMjpegUrl(undefined);
          setStreamState("error");
          setMessage(`${wsMessage.payload.code}: ${wsMessage.payload.message}`);
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
    };
  }, [cameraId, clearFirstFrameTimer, enabled]);

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

  const stopStream = useCallback(() => {
    if (!orchestratorWs.isOpen) {
      setStreamState("error");
      setMessage("WebSocket is not open yet");
      return;
    }

    try {
      clearFirstFrameTimer();
      setStreamState("stopping");
      setMessage("Stopping stream...");
      orchestratorWs.sendStreamStop({
        camera_id: cameraId,
      });
    } catch (error) {
      setStreamState("error");
      setMessage(errorMessage(error));
    }
  }, [cameraId, clearFirstFrameTimer]);

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
    handleStreamImageError,
  };
}
