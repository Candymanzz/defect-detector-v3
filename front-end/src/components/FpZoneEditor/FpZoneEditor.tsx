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
  const [selectedZoneId, setSelectedZoneId] = useState<string | null>(zones[0]?.id ?? null);
  const selectedZone = zones.find((zone) => zone.id === selectedZoneId) ?? zones[0] ?? null;
  const roiSvgPoints = roiPoints.map((point) => `${point.x},${point.y}`).join(" ");

  const updateZone = (zoneId: string | undefined, update: Partial<FpZoneNorm>) => {
    onChange(zones.map((zone) => (zone.id === zoneId ? { ...zone, ...update } : zone)));
  };

  const handleCanvasClick = (event: MouseEvent<HTMLDivElement>) => {
    if (disabled || !selectedZone) return;

    const rect = imageRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return;

    updateZone(selectedZone.id, {
      points_norm_heatmap: [
        ...selectedZone.points_norm_heatmap,
        {
          x: clamp01((event.clientX - rect.left) / rect.width),
          y: clamp01((event.clientY - rect.top) / rect.height),
        },
      ],
    });
  };

  const handleAddZone = () => {
    const id = createZoneId();
    onChange([
      ...zones,
      { id, note: `Исключающая зона ${zones.length + 1}`, points_norm_heatmap: [] },
    ]);
    setSelectedZoneId(id);
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
          alt="Исключающие зоны"
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
          {zones.map((zone) => {
            const points = zone.points_norm_heatmap.map((point) => `${point.x},${point.y}`).join(" ");
            const isSelected = zone === selectedZone;
            return (
              <g
                key={zone.id}
                className={isSelected ? "fp-zone-editor__zone fp-zone-editor__zone--selected" : "fp-zone-editor__zone"}
              >
                {zone.points_norm_heatmap.length >= 3 && <polygon points={points} />}
                {zone.points_norm_heatmap.length >= 2 && <polyline points={points} />}
                {zone.points_norm_heatmap.map((point, index) => (
                  <circle
                    key={`${zone.id}-${index}`}
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

      <div className="fp-zone-editor__panel">
        <div className="fp-zone-editor__header">
          <strong>Исключающие зоны</strong>
          <button
            type="button"
            disabled={disabled}
            onClick={handleAddZone}
          >
            Добавить зону
          </button>
        </div>

        {zones.length === 0 ? (
          <p className="fp-zone-editor__empty">
            Зоны необязательны. Добавьте их только для участков внутри ROI, которые нужно исключить.
          </p>
        ) : (
          <div className="fp-zone-editor__zones">
            {zones.map((zone, index) => (
              <button
                key={zone.id}
                className={
                  zone === selectedZone
                    ? "fp-zone-editor__zone-button fp-zone-editor__zone-button--active"
                    : "fp-zone-editor__zone-button"
                }
                type="button"
                onClick={() => setSelectedZoneId(zone.id ?? null)}
              >
                <span>{zone.note || `Исключающая зона ${index + 1}`}</span>
                <small>
                  {zone.points_norm_heatmap.length >= 3
                    ? `${zone.points_norm_heatmap.length} точек`
                    : `Нужно ещё ${3 - zone.points_norm_heatmap.length}`}
                </small>
              </button>
            ))}
          </div>
        )}

        {selectedZone && (
          <div className="fp-zone-editor__controls">
            <label>
              <span>Название зоны</span>
              <input
                value={selectedZone.note}
                disabled={disabled}
                onChange={(event) => updateZone(selectedZone.id, { note: event.target.value })}
              />
            </label>
            <div className="fp-zone-editor__actions">
              <button
                type="button"
                disabled={disabled || selectedZone.points_norm_heatmap.length === 0}
                onClick={() =>
                  updateZone(selectedZone.id, {
                    points_norm_heatmap: selectedZone.points_norm_heatmap.slice(0, -1),
                  })
                }
              >
                Удалить точку
              </button>
              <button
                type="button"
                disabled={disabled || selectedZone.points_norm_heatmap.length === 0}
                onClick={() => updateZone(selectedZone.id, { points_norm_heatmap: [] })}
              >
                Очистить
              </button>
              <button
                className="fp-zone-editor__delete"
                type="button"
                disabled={disabled}
                onClick={() => onChange(zones.filter((zone) => zone.id !== selectedZone.id))}
              >
                Удалить зону
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function createZoneId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `fp-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}