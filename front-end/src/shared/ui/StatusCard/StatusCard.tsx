import type { KeyboardEvent } from "react";
import { PreviewImage } from "../PreviewImage";
import "./StatusCard.css";

type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  isSelected?: boolean;
  onOpen: () => void;
  onSelect: () => void;
};

export function StatusCard({ cameraId, objectName, imageUrl, isSelected = false, onOpen, onSelect }: StatusCardProps) {
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect();
    }
  };

  return (
    <div
      className={isSelected ? "camera-card camera-card--selected" : "camera-card"}
      tabIndex={0}
      aria-pressed={isSelected}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
    >
      <div className="camera-card__image-wrap">
        <PreviewImage
          key={imageUrl ?? `camera-${cameraId}-offline`}
          alt={`${objectName}, камера ${cameraId}`}
          className="camera-card__image"
          placeholderClassName="camera-card__image-placeholder"
          src={imageUrl}
        />
      </div>

      <div className="camera-card__footer">
        <strong>Camera {cameraId}</strong>
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
  );
}
