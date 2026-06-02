import { PreviewImage } from "../PreviewImage";
import "./StatusCard.css";
import type { KeyboardEvent, MouseEvent } from "react";
type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  selected: boolean;
  onSelect: () => void;
  onOpen: () => void;
};

export function StatusCard({ cameraId, objectName, imageUrl, selected, onSelect, onOpen }: StatusCardProps) {
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect();
    }
  };

  const handleOpenClick = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    onOpen();
  };

  return (
    <div
      className={selected ? "camera-card selected" : "camera-card"}
      role="button"
      tabIndex={0}
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
        <button
          className="footer-button"
          type="button"
          onClick={handleOpenClick}
        >
          Открыть
        </button>
        <strong>Camera {cameraId}</strong>
      </div>
    </div>
  );
}
