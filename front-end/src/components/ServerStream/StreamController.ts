import { useCallback, useEffect, useRef, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { orchestratorWs } from "../../shared/ws";
import type { WsConnectionStatus } from "../../shared/ws";

export type StreamState = "idle" | "starting" | "playing" | "stopping" | "error";

type UseStreamControllerOptions = {
  cameraId: number;
  enabled: boolean;
  maxFps?: number;
};

const DEFAULT_MAX_FPS = 20;

export function useStreamController({ cameraId, enabled, maxFps = DEFAULT_MAX_FPS }: UseStreamControllerOptions) {
  const [streamState, setStreamState] = useState<StreamState>("idle");
  const [message, setMessage] = useState("Стрим остановлен");
  const [mjpegUrl, setMjpegUrl] = useState<string>();
  const [status, setStatus] = useState<WsConnectionStatus>(orchestratorWs.snapshot);
  const streamStateRef = useRef(streamState);

  useEffect(() => {
    streamStateRef.current = streamState;
  }, [streamState]);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    orchestratorWs.connect();

    const unsubscribeStatus = orchestratorWs.onStatus(setStatus);
    const unsubscribeMessage = orchestratorWs.onMessage((wsMessage) => {
      if (wsMessage.type === "server.stream_started") {
        if (wsMessage.payload.camera_id !== cameraId) {
          return;
        }

        const streamPath = wsMessage.payload.mjpeg_path ?? `/api/camera/${cameraId}/stream.mjpeg`;

        setMjpegUrl(orchestratorApi.url(streamPath));
        setStreamState("playing");
        setMessage(`Стрим запущен: ${wsMessage.payload.max_fps} FPS`);
        return;
      }

      if (wsMessage.type === "server.stream_stopped") {
        if (wsMessage.payload.camera_id !== cameraId) {
          return;
        }

        setMjpegUrl(undefined);
        setStreamState("idle");
        setMessage("Стрим остановлен");
        return;
      }

      if (wsMessage.type === "server.error") {
        setMjpegUrl(undefined);
        setStreamState("error");
        setMessage(`${wsMessage.payload.code}: ${wsMessage.payload.message}`);
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
    };
  }, [cameraId, enabled]);

  const startStream = useCallback(() => {
    if (!orchestratorWs.isOpen) {
      setStreamState("error");
      setMessage("WebSocket еще не подключен");
      return;
    }

    try {
      setStreamState("starting");
      setMessage("Запускаем стрим...");
      orchestratorWs.sendStreamStart({
        camera_id: cameraId,
        max_fps: maxFps,
      });
    } catch (error) {
      setStreamState("error");
      setMessage(errorMessage(error));
    }
  }, [cameraId, maxFps]);

  const stopStream = useCallback(() => {
    if (!orchestratorWs.isOpen) {
      setStreamState("error");
      setMessage("WebSocket еще не подключен");
      return;
    }

    try {
      setStreamState("stopping");
      setMessage("Останавливаем стрим...");
      orchestratorWs.sendStreamStop({
        camera_id: cameraId,
      });
    } catch (error) {
      setStreamState("error");
      setMessage(errorMessage(error));
    }
  }, [cameraId]);

  const isPlaying = streamState === "playing";
  const isBusy = streamState === "starting" || streamState === "stopping";
  const isSocketOpen = status.state === "open";

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
  };
}
