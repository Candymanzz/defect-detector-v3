import { useEffect, useState } from "react";
import { orchestratorApi } from "../../shared/api";
import { Button } from "../../shared/ui/Button";
import { useStreamController } from "./StreamController";
import "./ServerStream.css";

type ServerStreamProps = {
  isOpen: boolean;
  cameraId: number;
  title?: string;
  onClose: () => void;
};

export function ServerStream({ isOpen, cameraId, title, onClose }: ServerStreamProps) {
  const [selectedCameraId, setSelectedCameraId] = useState(cameraId);
  const [cameraIds, setCameraIds] = useState<number[]>([cameraId]);
  const [pendingCameraId, setPendingCameraId] = useState<number | null>(null);
  const {
    status,
    streamState,
    message,
    mjpegUrl,
    isPlaying,
    canStart,
    canStop,
    startStream,
    stopStream,
    prepareCameraSwitch,
    handleStreamImageError,
  } = useStreamController({
    cameraId: selectedCameraId,
    enabled: isOpen,
  });
  const streamTitle = title ?? "Стрим камер";

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    let isActive = true;
    orchestratorApi
      .listCameras()
      .then(({ cameras }) => {
        if (!isActive) {
          return;
        }
        setCameraIds([...new Set([cameraId, ...cameras])].sort((left, right) => left - right));
      })
      .catch(() => {
        // Keep the initially selected camera available if the camera list cannot be loaded.
      });

    return () => {
      isActive = false;
    };
  }, [cameraId, isOpen]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, onClose]);

  const handleCameraChange = async (nextCameraId: number) => {
    if (nextCameraId === selectedCameraId || pendingCameraId !== null) {
      return;
    }

    setPendingCameraId(nextCameraId);
    const previousStreamStopped = await stopStream();
    if (previousStreamStopped) {
      prepareCameraSwitch();
      setSelectedCameraId(nextCameraId);
    }
    setPendingCameraId(null);
  };

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="server-stream"
      onMouseDown={onClose}
    >
      <section
        aria-label={streamTitle}
        aria-modal="true"
        className="server-stream__modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="server-stream__header">
          <div className="server-stream__heading">
            <h2>{streamTitle}</h2>
            <span
              className="server-stream__connection"
              data-state={status.state}
            >
              WS: {status.state}
            </span>
            <div
              aria-label="Выбор камеры"
              className="server-stream__camera-selector"
              role="group"
            >
              <span>Камеры</span>
              <div className="server-stream__camera-tiles">
                {cameraIds.map((availableCameraId) => {
                  const isActive = availableCameraId === selectedCameraId;

                  return (
                    <Button
                      key={availableCameraId}
                      aria-pressed={isActive}
                      className="server-stream__camera-tile"
                      disabled={pendingCameraId !== null}
                      variant={isActive ? "primary" : "warning"}
                      onClick={() => void handleCameraChange(availableCameraId)}
                    >
                      {availableCameraId}
                    </Button>
                  );
                })}
              </div>
            </div>
          </div>
          <button
            aria-label="Закрыть стрим"
            className="server-stream__close"
            type="button"
            onClick={onClose}
          >
            x
          </button>
        </header>

        <div className="server-stream__player">
          {mjpegUrl ? (
            <img
              key={mjpegUrl}
              alt={streamTitle}
              className="server-stream__image"
              src={mjpegUrl}
              onError={handleStreamImageError}
            />
          ) : (
            <div
              className="server-stream__placeholder"
              data-state={streamState}
            >
              {message}
            </div>
          )}
        </div>

        <footer className="server-stream__footer">
          <span
            className="server-stream__status"
            data-state={streamState}
          >
            {isPlaying ? "Стрим активен" : message}
          </span>

          <div className="server-stream__controls">
            <Button
              className="server-stream__button"
              disabled={!canStart}
              onClick={startStream}
            >
              Пуск стрима
            </Button>
            <button
              className="server-stream__button server-stream__button--stop"
              disabled={!canStop}
              type="button"
              onClick={() => void stopStream()}
            >
              Стоп стрим
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}
