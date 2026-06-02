import type { MouseEvent } from "react";
import { useRef } from "react";
import "./RoiContourEditor.css";

export type NormPoint = {
  x: number;
  y: number;
};

type RoiContourEditorProps = {
  imageUrl: string;
  points: NormPoint[];
  disabled?: boolean;
  onChange: (points: NormPoint[]) => void;
};

export function RoiContourEditor({ imageUrl, points, disabled = false, onChange }: RoiContourEditorProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const svgPoints = points.map((point) => `${point.x},${point.y}`).join(" ");

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled) {
      return;
    }

    const rect = imageRef.current?.getBoundingClientRect();

    if (!rect || rect.width <= 0 || rect.height <= 0) {
      return;
    }

    const nextPoint: NormPoint = {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };

    onChange([...points, nextPoint]);
  };

  const handleRemoveLastPoint = () => {
    onChange(points.slice(0, -1));
  };

  const handleClear = () => {
    onChange([]);
  };

  const handleUseFullFrame = () => {
    onChange(createFullFramePolygon());
  };

  const actionButtons = [
    {
      title: "Удалить точку",
      onClick: handleRemoveLastPoint,
      disabled: disabled || points.length === 0,
    },
    {
      title: "Очистить",
      onClick: handleClear,
      disabled: disabled || points.length === 0,
    },
    {
      title: "Весь кадр",
      onClick: handleUseFullFrame,
      disabled,
    },
  ];

  return (
    <div className="roi-editor">
      <div
        className="roi-editor__canvas"
        onClick={handleCanvasClick}
      >
        <img
          ref={imageRef}
          src={imageUrl}
          alt="ROI"
        />
        <svg
          className="roi-editor__overlay"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
        >
          {points.length >= 3 && <polygon points={svgPoints} />}
          {points.length >= 2 && <polyline points={svgPoints} />}
          {points.map((point, index) => (
            <circle
              key={`${point.x}-${point.y}-${index}`}
              cx={point.x}
              cy={point.y}
              r="0.012"
            />
          ))}
        </svg>
      </div>

      <div className="roi-editor__actions">
        {actionButtons.map((button) => (
          <button
            key={button.title}
            type="button"
            disabled={button.disabled}
            onClick={button.onClick}
          >
            {button.title}
          </button>
        ))}
      </div>
    </div>
  );
}

function createFullFramePolygon(): NormPoint[] {
  return [
    { x: 0, y: 0 },
    { x: 1, y: 0 },
    { x: 1, y: 1 },
    { x: 0, y: 1 },
  ];
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}
