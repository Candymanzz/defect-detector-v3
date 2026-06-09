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
  const cleanupStopTimerRef = useRef<number | null>(null);

  const updateStreamState = useCallback((nextState: StreamState) => {
    streamStateRef.current = nextState;
    setStreamState(nextState);
  }, []);

  useEffect(() => {
    streamStateRef.current = streamState;
  }, [streamState]);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    if (cleanupStopTimerRef.current !== null) {
      window.clearTimeout(cleanupStopTimerRef.current);
      cleanupStopTimerRef.current = null;
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
          updateStreamState("playing");
          setMessage(`Stream started: ${wsMessage.payload.max_fps} FPS`);
          return;
        }

        case "server.preview_frame":
          if (wsMessage.payload.camera_id !== cameraId || streamStateRef.current !== "playing") {
            return;
          }

          setMessage(`Stream active, frame ${wsMessage.payload.frame_id}`);
          return;

        case "server.stream_stopped":
          if (wsMessage.payload.camera_id !== cameraId) {
            return;
          }

          setMjpegUrl(undefined);
          updateStreamState("idle");
          setMessage("Stream stopped");
          return;

        case "server.error":
          if (streamStateRef.current !== "starting" && streamStateRef.current !== "stopping") {
            return;
          }
          if (!isStreamErrorCode(wsMessage.payload.code)) {
            return;
          }

          setMjpegUrl(undefined);
          updateStreamState("error");
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
        cleanupStopTimerRef.current = window.setTimeout(() => {
          cleanupStopTimerRef.current = null;
          orchestratorWs.stopStreamWhenPossible(cameraId);
        }, 0);
      }
    };
  }, [cameraId, enabled, updateStreamState]);

  const startStream = useCallback(() => {
    if (!orchestratorWs.isOpen) {
      updateStreamState("error");
      setMessage("WebSocket is not open yet");
      return;
    }

    try {
      setMjpegUrl(undefined);
      updateStreamState("starting");
      setMessage("Starting stream...");
      orchestratorWs.sendStreamStart({
        camera_id: cameraId,
        max_fps: maxFps,
      });
    } catch (error) {
      updateStreamState("error");
      setMessage(errorMessage(error));
    }
  }, [cameraId, maxFps, updateStreamState]);

  const handleStreamImageError = useCallback(() => {
    setMjpegUrl(undefined);
    updateStreamState("error");
    setMessage("MJPEG stream image failed to load");
  }, [updateStreamState]);

  const handleStreamImageLoad = useCallback(() => {
    setMessage("Stream active");
  }, []);

  const stopStream = useCallback(() => {
    if (!orchestratorWs.isOpen) {
      updateStreamState("error");
      setMessage("WebSocket is not open yet");
      return;
    }

    try {
      updateStreamState("stopping");
      setMessage("Stopping stream...");
      orchestratorWs.sendStreamStop({
        camera_id: cameraId,
      });
    } catch (error) {
      updateStreamState("error");
      setMessage(errorMessage(error));
    }
  }, [cameraId, updateStreamState]);

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
    handleStreamImageLoad,
    handleStreamImageError,
  };
}

function isStreamErrorCode(code: string) {
  return code === "stream_disabled" || code === "stream_already_active" || code === "stream_start_failed";
}
