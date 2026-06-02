import { useEffect } from "react";
import { useStreamController } from "./StreamController";
import "./ServerStream.css";

type ServerStreamProps = {
  isOpen: boolean;
  cameraId: number;
  title?: string;
  onClose: () => void;
};

export function ServerStream({ isOpen, cameraId, title, onClose }: ServerStreamProps) {
  const { status, streamState, message, mjpegUrl, isPlaying, canStart, canStop, startStream, stopStream } =
    useStreamController({
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
          <div>
            <h2>{streamTitle}</h2>
            <span
              className="server-stream__connection"
              data-state={status.state}
            >
              WS: {status.state}
            </span>
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
            <button
              className="server-stream__button server-stream__button--start"
              disabled={!canStart}
              type="button"
              onClick={startStream}
            >
              Пуск стрима
            </button>
            <button
              className="server-stream__button server-stream__button--stop"
              disabled={!canStop}
              type="button"
              onClick={stopStream}
            >
              Стоп стрим
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}
