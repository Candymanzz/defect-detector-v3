import type { GeometryInspectResponse } from "../../shared/api";
import "./GeometryDeviationViewer.css";

type GeometryDeviationViewerProps = {
  geometry?: GeometryInspectResponse | null;
  loading?: boolean;
  error?: string | null;
};

const VIEW = 220;
const CENTER = VIEW / 2;
const PLOT_RADIUS = 78;

export function GeometryDeviationViewer({ geometry, loading, error }: GeometryDeviationViewerProps) {
  if (loading) {
    return (
      <figure className="geometry-deviation">
        <figcaption>Геометрия</figcaption>
        <div className="geometry-deviation__placeholder">Загрузка…</div>
      </figure>
    );
  }

  if (error) {
    return (
      <figure className="geometry-deviation">
        <figcaption>Геометрия</figcaption>
        <div className="geometry-deviation__placeholder geometry-deviation__placeholder--error">{error}</div>
      </figure>
    );
  }

  if (!geometry) {
    return (
      <figure className="geometry-deviation">
        <figcaption>Геометрия</figcaption>
        <div className="geometry-deviation__placeholder">Нет снимка геометрии</div>
      </figure>
    );
  }

  const shiftX = asFinite(geometry.shiftXmm);
  const shiftY = asFinite(geometry.shiftYmm);
  const deviationRadius = asFinite(geometry.deviationRadiusMm) ?? Math.hypot(shiftX ?? 0, shiftY ?? 0);
  const maxShift = asFinite(geometry.maxShiftMm) ?? 0.5;
  const rotationDeg = asFinite(geometry.rotationDeg);
  const concentricityMm = asFinite(geometry.concentricityMm);
  const passed = geometry.overallPass === true || geometry.status === "PASS";
  const alignmentPassed = geometry.alignmentPass !== false && (geometry.alignmentPass === true || passed);

  const scale = maxShift > 0 ? PLOT_RADIUS / Math.max(maxShift, deviationRadius || 0, 0.01) : PLOT_RADIUS;
  const pointX = CENTER + (shiftX ?? 0) * scale;
  const pointY = CENTER - (shiftY ?? 0) * scale;
  const deviationPx = Math.max(2, deviationRadius * scale);
  const tolerancePx = Math.max(2, maxShift * scale);

  return (
    <figure
      className="geometry-deviation"
      data-pass={passed ? "true" : "false"}
    >
      <figcaption>Отклонение от эталона</figcaption>
      <div className="geometry-deviation__body">
        <svg
          aria-label={`Радиус отклонения ${formatMm(deviationRadius)} мм`}
          className="geometry-deviation__plot"
          role="img"
          viewBox={`0 0 ${VIEW} ${VIEW}`}
        >
          <circle
            className="geometry-deviation__grid"
            cx={CENTER}
            cy={CENTER}
            r={PLOT_RADIUS}
          />
          <circle
            className="geometry-deviation__tolerance"
            cx={CENTER}
            cy={CENTER}
            r={tolerancePx}
          />
          <line
            className="geometry-deviation__axis"
            x1={CENTER - PLOT_RADIUS}
            x2={CENTER + PLOT_RADIUS}
            y1={CENTER}
            y2={CENTER}
          />
          <line
            className="geometry-deviation__axis"
            x1={CENTER}
            x2={CENTER}
            y1={CENTER - PLOT_RADIUS}
            y2={CENTER + PLOT_RADIUS}
          />
          <circle
            className="geometry-deviation__radius"
            cx={CENTER}
            cy={CENTER}
            data-pass={alignmentPassed ? "true" : "false"}
            r={deviationPx}
          />
          <line
            className="geometry-deviation__vector"
            data-pass={alignmentPassed ? "true" : "false"}
            markerEnd="url(#geometry-deviation-arrow)"
            x1={CENTER}
            x2={pointX}
            y1={CENTER}
            y2={pointY}
          />
          <circle
            className="geometry-deviation__origin"
            cx={CENTER}
            cy={CENTER}
            r={3.5}
          />
          <circle
            className="geometry-deviation__point"
            cx={pointX}
            cy={pointY}
            data-pass={alignmentPassed ? "true" : "false"}
            r={4.5}
          />
          <defs>
            <marker
              id="geometry-deviation-arrow"
              markerHeight="7"
              markerWidth="7"
              orient="auto"
              refX="5"
              refY="3.5"
            >
              <path
                className="geometry-deviation__arrow-head"
                d="M0,0 L7,3.5 L0,7 Z"
                data-pass={alignmentPassed ? "true" : "false"}
              />
            </marker>
          </defs>
          <text
            className="geometry-deviation__axis-label"
            textAnchor="middle"
            x={CENTER + PLOT_RADIUS - 8}
            y={CENTER - 6}
          >
            +X
          </text>
          <text
            className="geometry-deviation__axis-label"
            textAnchor="start"
            x={CENTER + 6}
            y={CENTER - PLOT_RADIUS + 12}
          >
            +Y
          </text>
        </svg>

        <dl className="geometry-deviation__metrics">
          <div>
            <dt>Радиус отклонения</dt>
            <dd data-emphasis="true">{formatMm(deviationRadius)} мм</dd>
          </div>
          <div>
            <dt>Допуск (maxShift)</dt>
            <dd>{formatMm(maxShift)} мм</dd>
          </div>
          <div>
            <dt>dx / dy</dt>
            <dd>
              {formatMm(shiftX)} / {formatMm(shiftY)} мм
            </dd>
          </div>
          <div>
            <dt>Поворот</dt>
            <dd>{formatDeg(rotationDeg)}°</dd>
          </div>
          <div>
            <dt>Концентричность</dt>
            <dd>{formatMm(concentricityMm)} мм</dd>
          </div>
          <div>
            <dt>Вердикт</dt>
            <dd data-pass={passed ? "true" : "false"}>{passed ? "Годен" : "Брак"}</dd>
          </div>
        </dl>
      </div>
    </figure>
  );
}

function asFinite(value: unknown): number | undefined {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return undefined;
  }
  return value;
}

function formatMm(value: number | undefined) {
  if (value === undefined) {
    return "—";
  }
  if (Math.abs(value) >= 100) {
    return value.toFixed(0);
  }
  return value.toFixed(3);
}

function formatDeg(value: number | undefined) {
  if (value === undefined) {
    return "—";
  }
  if (Math.abs(value) >= 100) {
    return value.toFixed(0);
  }
  return value.toFixed(2);
}
