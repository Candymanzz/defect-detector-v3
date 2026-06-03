import type { KeyboardEvent } from "react";
import { PreviewImage } from "../PreviewImage";
import "./StatusCard.css";

type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  onOpen: () => void;
};

export function StatusCard({ cameraId, objectName, imageUrl, onOpen }: StatusCardProps) {
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onOpen();
    }
  };

  return (
    <div
      className="camera-card"
      role="button"
      tabIndex={0}
      onClick={onOpen}
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
      </div>
    </div>
  );
}
