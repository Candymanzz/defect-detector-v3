import type { KeyboardEvent } from "react";
import { PreviewImage } from "../PreviewImage";
import "./StatusCard.css";

type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  currentFrameId?: string;
  inspectionFrameId?: string;
  isSelected?: boolean;
  isInspectionEnabled?: boolean;
  isInspectionActionDisabled?: boolean;
  inspectionActionLabel?: string;
  inspectionStatus?: string;
  inspectionResult?: "pass" | "fail";
  onOpen: () => void;
  onSelect: () => void;
  onInspectionToggle: () => void;
};

export function StatusCard({
  cameraId,
  objectName,
  imageUrl,
  currentFrameId,
  inspectionFrameId,
  isSelected = false,
  isInspectionEnabled = true,
  isInspectionActionDisabled = false,
  inspectionActionLabel = "Stop",
  inspectionStatus,
  inspectionResult,
  onOpen,
  onSelect,
  onInspectionToggle,
}: StatusCardProps) {
  const classNames = [
    "camera-card",
    isSelected ? "camera-card--selected" : "",
    inspectionResult ? `camera-card--${inspectionResult}` : "",
  ]
    .filter(Boolean)
    .join(" ");

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.target !== event.currentTarget) {
      return;
    }

    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect();
    }
  };

  return (
    <div
      className={classNames}
      tabIndex={0}
      aria-pressed={isSelected}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
    >
      <div className="camera-card__image-wrap">
        <PreviewImage
          alt={`${objectName}, камера ${cameraId}`}
          className="camera-card__image"
          placeholderClassName="camera-card__image-placeholder"
          src={imageUrl}
        />
      </div>

      <div className="camera-card__frame-ids">
        <span>Current frame: {currentFrameId ?? "-"}</span>
        <span>Inspection frame: {inspectionFrameId ?? "-"}</span>
      </div>

      <div className="camera-card__footer">
        <strong>Camera {cameraId}</strong>
        <div className="camera-card__actions">
          <button
            className={isInspectionEnabled ? "camera-card__stop" : "camera-card__start"}
            type="button"
            disabled={isInspectionActionDisabled}
            title={inspectionStatus}
            onClick={(event) => {
              event.stopPropagation();
              onInspectionToggle();
            }}
          >
            {inspectionActionLabel}
          </button>
          <button
            className="camera-card__open"
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              onOpen();
            }}
          >
            Открыть
          </button>
        </div>
      </div>
      {inspectionStatus && <div className="camera-card__stop-status">{inspectionStatus}</div>}
    </div>
  );
}
