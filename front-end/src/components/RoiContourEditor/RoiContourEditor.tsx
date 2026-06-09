import type { MouseEvent } from "react";
import { useCallback, useLayoutEffect, useRef, useState } from "react";
import { Button } from "../../shared/ui/Button";
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

type ImageBounds = {
  left: number;
  top: number;
  width: number;
  height: number;
};

export function RoiContourEditor({ imageUrl, points, disabled = false, onChange }: RoiContourEditorProps) {
  const canvasRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const [imageBounds, setImageBounds] = useState<ImageBounds | null>(null);
  const svgPoints = imageBounds
    ? points.map((point) => `${point.x * imageBounds.width},${point.y * imageBounds.height}`).join(" ")
    : "";

  const updateImageBounds = useCallback(() => {
    const canvasRect = canvasRef.current?.getBoundingClientRect();
    const imageRect = imageRef.current?.getBoundingClientRect();

    if (!canvasRect || !imageRect || imageRect.width <= 0 || imageRect.height <= 0) {
      setImageBounds(null);
      return;
    }

    setImageBounds({
      left: imageRect.left - canvasRect.left,
      top: imageRect.top - canvasRect.top,
      width: imageRect.width,
      height: imageRect.height,
    });
  }, []);

  useLayoutEffect(() => {
    const canvas = canvasRef.current;
    const image = imageRef.current;
    if (!canvas || !image) {
      return;
    }

    const observer = new ResizeObserver(updateImageBounds);
    observer.observe(canvas);
    observer.observe(image);
    updateImageBounds();

    return () => observer.disconnect();
  }, [imageUrl, updateImageBounds]);

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled) {
      return;
    }

    const rect = imageRef.current?.getBoundingClientRect();

    if (!rect || rect.width <= 0 || rect.height <= 0) {
      return;
    }

    if (
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom
    ) {
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
        ref={canvasRef}
        className="roi-editor__canvas"
        onClick={handleCanvasClick}
      >
        <img
          ref={imageRef}
          src={imageUrl}
          alt="ROI"
          onLoad={updateImageBounds}
        />
        {imageBounds && (
          <svg
            className="roi-editor__overlay"
            style={{
              left: imageBounds.left,
              top: imageBounds.top,
              width: imageBounds.width,
              height: imageBounds.height,
            }}
            viewBox={`0 0 ${imageBounds.width} ${imageBounds.height}`}
          >
            {points.length >= 3 && <polygon points={svgPoints} />}
            {points.length >= 2 && <polyline points={svgPoints} />}
            {points.map((point, index) => (
              <circle
                key={`${point.x}-${point.y}-${index}`}
                cx={point.x * imageBounds.width}
                cy={point.y * imageBounds.height}
                r="6"
              />
            ))}
          </svg>
        )}
      </div>

      <div className="roi-editor__actions">
        {actionButtons.map((button) => (
          <Button
            key={button.title}
            type="button"
            disabled={button.disabled}
            variant="ghost"
            onClick={button.onClick}
          >
            {button.title}
          </Button>
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
