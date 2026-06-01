import { PreviewImage } from "../PreviewImage";
import "./StatusCard.css";
import { useState } from "react";
type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  onClick: () => void;
};

export function StatusCard({ cameraId, objectName, imageUrl, onClick }: StatusCardProps) {
  const [selected, setSelected] = useState(false);
  return (
    <button
      className={selected ? "camera-card selected" : "camera-card"}
      type="button"
      onClick={() => setSelected(!selected)}
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
          onClick={onClick}
        >
          Открыть
        </button>
        <strong>Camera {cameraId}</strong>
      </div>
    </button>
  );
}
