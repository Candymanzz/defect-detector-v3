import { useRef, useState } from "react";
import type { MouseEvent } from "react";
import type { FpZoneNorm, InterestPointNorm } from "../../shared/ws";
import "./FpZoneEditor.css";

type FpZoneEditorProps = {
  imageUrl: string;
  roiPoints?: InterestPointNorm[];
  zones: FpZoneNorm[];
  disabled?: boolean;
  onChange: (zones: FpZoneNorm[]) => void;
};

export function FpZoneEditor({
  imageUrl,
  roiPoints = [],
  zones,
  disabled = false,
  onChange,
}: FpZoneEditorProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const safeSelectedIndex = zones.length === 0 ? -1 : Math.min(selectedIndex, zones.length - 1);
  const selectedZone = safeSelectedIndex >= 0 ? zones[safeSelectedIndex] : undefined;
  const roiSvgPoints = roiPoints.map((point) => `${point.x},${point.y}`).join(" ");

  const updateZone = (zoneIndex: number, update: Partial<FpZoneNorm>) => {
    onChange(zones.map((zone, index) => (index === zoneIndex ? { ...zone, ...update } : zone)));
  };

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled) return;

    const rect = imageRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return;

    const nextPoint = {
      x: clamp01((event.clientX - rect.left) / rect.width),
      y: clamp01((event.clientY - rect.top) / rect.height),
    };

    if (!selectedZone) {
      onChange([createEmptyZone([nextPoint])]);
      setSelectedIndex(0);
      return;
    }

    updateZone(safeSelectedIndex, {
      points_norm_heatmap: [...selectedZone.points_norm_heatmap, nextPoint],
    });
  };

  const handleAddZone = () => {
    onChange([...zones, createEmptyZone()]);
    setSelectedIndex(zones.length);
  };

  const handleRemoveLastPoint = () => {
    if (!selectedZone) return;
    updateZone(safeSelectedIndex, {
      points_norm_heatmap: selectedZone.points_norm_heatmap.slice(0, -1),
    });
  };

  const handleClearZone = () => {
    if (!selectedZone) return;
    updateZone(safeSelectedIndex, { points_norm_heatmap: [] });
  };

  const handleDeleteZone = () => {
    if (!selectedZone) return;
    onChange(zones.filter((_, index) => index !== safeSelectedIndex));
    setSelectedIndex(Math.max(0, safeSelectedIndex - 1));
  };

  return (
    <div className="fp-zone-editor">
      <div
        className="fp-zone-editor__canvas"
        onClick={handleCanvasClick}
      >
        <img
          ref={imageRef}
          src={imageUrl}
          alt={"\u0418\u0441\u043a\u043b\u044e\u0447\u0430\u044e\u0449\u0438\u0435 \u0437\u043e\u043d\u044b"}
        />
        <svg
          className="fp-zone-editor__overlay"
          viewBox="0 0 1 1"
          preserveAspectRatio="none"
        >
          {roiPoints.length >= 3 && (
            <polygon
              className="fp-zone-editor__roi"
              points={roiSvgPoints}
            />
          )}

          {zones.map((zone, zoneIndex) => {
            const points = zone.points_norm_heatmap.map((point) => `${point.x},${point.y}`).join(" ");
            const isSelected = zoneIndex === safeSelectedIndex;

            return (
              <g
                key={zone.id ?? zoneIndex}
                className={isSelected ? "fp-zone-editor__zone fp-zone-editor__zone--selected" : "fp-zone-editor__zone"}
              >
                {zone.points_norm_heatmap.length >= 3 && <polygon points={points} />}
                {zone.points_norm_heatmap.length >= 2 && <polyline points={points} />}
                {zone.points_norm_heatmap.map((point, pointIndex) => (
                  <circle
                    key={`${zone.id ?? zoneIndex}-${pointIndex}`}
                    cx={point.x}
                    cy={point.y}
                    r="0.012"
                  />
                ))}
              </g>
            );
          })}
        </svg>
      </div>

      <div className="fp-zone-editor__actions">
        <button
          type="button"
          disabled={disabled}
          onClick={handleAddZone}
        >
          {"\u041d\u043e\u0432\u0430\u044f \u0437\u043e\u043d\u0430"}
        </button>
        <button
          type="button"
          disabled={disabled || !selectedZone || selectedZone.points_norm_heatmap.length === 0}
          onClick={handleRemoveLastPoint}
        >
          {"\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0442\u043e\u0447\u043a\u0443"}
        </button>
        <button
          type="button"
          disabled={disabled || !selectedZone || selectedZone.points_norm_heatmap.length === 0}
          onClick={handleClearZone}
        >
          {"\u041e\u0447\u0438\u0441\u0442\u0438\u0442\u044c"}
        </button>
        <button
          className="fp-zone-editor__delete"
          type="button"
          disabled={disabled || !selectedZone}
          onClick={handleDeleteZone}
        >
          {"\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0437\u043e\u043d\u0443"}
        </button>
        {zones.length > 1 && (
          <div className="fp-zone-editor__zone-tabs">
            {zones.map((zone, index) => (
              <button
                key={zone.id ?? index}
                className={index === safeSelectedIndex ? "fp-zone-editor__zone-tab is-active" : "fp-zone-editor__zone-tab"}
                type="button"
                onClick={() => setSelectedIndex(index)}
              >
                {index + 1}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function createEmptyZone(points: FpZoneNorm["points_norm_heatmap"] = []): FpZoneNorm {
  return {
    id: createZoneId(),
    note: "\u0418\u0441\u043a\u043b\u044e\u0447\u0430\u044e\u0449\u0430\u044f \u0437\u043e\u043d\u0430",
    points_norm_heatmap: points,
  };
}

function createZoneId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `fp-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}
