import { Button } from "../Button";
import { PreviewImage } from "../PreviewImage";
import { StatusPill } from "../StatusPill";
import "./StatusCard.css";

type SignalState = "success" | "danger";

type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  isSelected?: boolean;
  onOpen: () => void;
  onSelect: () => void;
};

export function StatusCard({ cameraId, objectName, imageUrl, isSelected = false, onOpen, onSelect }: StatusCardProps) {
  const signalState: SignalState = imageUrl ? "success" : "danger";
  const signalText = imageUrl ? "Online" : "No signal";

  return (
    <article className={isSelected ? "camera-card camera-card--selected" : "camera-card"}>
      <button
        className="camera-card__select"
        type="button"
        aria-pressed={isSelected}
        onClick={onSelect}
      >
        <PreviewImage
          key={imageUrl ?? `camera-${cameraId}-offline`}
          alt={`${objectName}, camera ${cameraId}`}
          className="camera-card__image"
          placeholderClassName="camera-card__image-placeholder"
          src={imageUrl}
        />
      </button>

      <div className="camera-card__footer">
        <div className="camera-card__meta">
          <strong>Camera {cameraId}</strong>
          <StatusPill state={signalState}>{signalText}</StatusPill>
        </div>
        <Button
          className="camera-card__open"
          type="button"
          variant="ghost"
          onClick={onOpen}
        >
          Открыть
        </Button>
      </div>
    </article>
  );
}
