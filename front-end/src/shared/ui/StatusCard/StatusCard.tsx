import { Button } from "../Button";
import { PreviewImage } from "../PreviewImage";
import { StatusPill } from "../StatusPill";
import "./StatusCard.css";

type StatusCardProps = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
  isSelected?: boolean;
  signalState: "waiting" | "online" | "offline";
  onOpen: () => void;
  onSelect: () => void;
};

export function StatusCard({
  cameraId,
  objectName,
  imageUrl,
  isSelected = false,
  signalState,
  onOpen,
  onSelect,
}: StatusCardProps) {
  const signal = getSignalPresentation(signalState);

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
          <StatusPill state={signal.pillState}>{signal.text}</StatusPill>
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

function getSignalPresentation(signalState: StatusCardProps["signalState"]) {
  if (signalState === "online") {
    return { pillState: "success" as const, text: "Online" };
  }

  if (signalState === "offline") {
    return { pillState: "danger" as const, text: "No signal" };
  }

  return { pillState: "warning" as const, text: "Waiting" };
}
