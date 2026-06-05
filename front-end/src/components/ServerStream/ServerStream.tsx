import { useEffect } from "react";
import { Button } from "../../shared/ui/Button";
import "../ModalWrapper/ModalWrapper.css";
import { useStreamController } from "./StreamController";
import "./ServerStream.css";

type ServerStreamProps = {
  isOpen: boolean;
  cameraId: number;
  title?: string;
  onClose: () => void;
};

export function ServerStream({ isOpen, cameraId, title, onClose }: ServerStreamProps) {
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
    handleStreamImageError,
  } = useStreamController({
    cameraId,
    enabled: isOpen,
  });
  const streamTitle = title ?? `Стрим камеры ${cameraId}`;

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

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="modal-backdrop server-stream"
      onMouseDown={onClose}
    >
      <section
        aria-label={streamTitle}
        aria-modal="true"
        className="modal server-stream__modal"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal__header server-stream__header">
          <div>
            <h2>{streamTitle}</h2>
            <span
              className="server-stream__connection"
              data-state={status.state}
            >
              WS: {status.state}
            </span>
          </div>
          <Button
            aria-label="Закрыть стрим"
            className="modal__close"
            type="button"
            variant="ghost"
            onClick={onClose}
          >
            x
          </Button>
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
              disabled={!canStart}
              type="button"
              variant="primary"
              onClick={startStream}
            >
              Пуск стрима
            </Button>
            <Button
              disabled={!canStop}
              type="button"
              variant="ghost"
              onClick={stopStream}
            >
              Стоп стрим
            </Button>
          </div>
        </footer>
      </section>
    </div>
  );
}
