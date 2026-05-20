import { PreviewImage } from "../PreviewImage";

type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  onClick: () => void;
};

export function StatusCard({ cameraId, objectName, imageUrl, onClick }: StatusCardProps) {
  return (
    <button className="camera-card" type="button" onClick={onClick}>
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
        <span>{objectName}</span>
        <strong>Camera {cameraId}</strong>
      </div>
    </button>
  );
}
