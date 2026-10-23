import type { DetectorLineZone, DetectorResult } from "../../shared/ws";
import "./LineZonesOverlay.css";

type LineZonesOverlayProps = {
  detectorResult?: DetectorResult;
  /** Frame size in px, to turn the search band (px) into a share of the frame. */
  frameWidth?: number;
  frameHeight?: number;
};

type ZoneName = "seam" | "edge";

const ZONE_TITLES: Record<ZoneName, string> = { seam: "Стык", edge: "Кромка" };

/**
 * Seam / edge zones drawn over the heatmap like the CppDetector overlay: search band, reference line, measured edge,
 * red points where the deviation is over tolerance. Coordinates arrive normalised 0..1 of the frame.
 */
export function LineZonesOverlay({ detectorResult, frameWidth, frameHeight }: LineZonesOverlayProps) {
  const zones = (["seam", "edge"] as const).flatMap((name) => {
    const zone = detectorResult?.lines?.[name];
    return zone?.axis && zone.axis.length > 1 ? [{ name, zone }] : [];
  });
  if (zones.length === 0) {
    return null;
  }

  return (
    <svg
      className="line-zones-overlay"
      viewBox="0 0 1 1"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {zones.map(({ name, zone }) => (
        <g
          key={name}
          className="line-zones-overlay__zone"
          data-zone={name}
          data-reject={zone.reject ? "true" : undefined}
        >
          <ZoneShapes
            zone={zone}
            frameWidth={frameWidth}
            frameHeight={frameHeight}
          />
        </g>
      ))}
    </svg>
  );
}

function ZoneShapes({
  zone,
  frameWidth,
  frameHeight,
}: {
  zone: DetectorLineZone;
  frameWidth?: number;
  frameHeight?: number;
}) {
  const axis = zone.axis ?? [];
  const states = zone.state ?? "";
  const edge = zone.edge ?? [];
  const ref = zone.ref ?? [];
  const bad = collectBadPoints(zone);

  return (
    <>
      {zone.reject && frameWidth && frameHeight && (
        <polygon
          className="line-zones-overlay__band"
          points={bandOutline(axis, zone.band ?? 0, frameWidth, frameHeight)}
        />
      )}
      <polyline
        className="line-zones-overlay__axis"
        points={toPoints(axis)}
      />
      {runs(states, 0).map(([from, to]) => (
        <g key={from}>
          <polyline
            className="line-zones-overlay__ref"
            points={toPoints(ref.slice(from, to))}
          />
          <polyline
            className="line-zones-overlay__edge"
            points={toPoints(edge.slice(from, to))}
          />
        </g>
      ))}
      {runs(states, 3).map(([from, to]) => (
        <polyline
          key={`gap-${from}`}
          className="line-zones-overlay__missing"
          points={toPoints(axis.slice(from, to))}
        />
      ))}
      {bad.map(([x, y], index) => (
        <circle
          key={index}
          className="line-zones-overlay__bad"
          cx={x}
          cy={y}
          r={0.006}
        />
      ))}
    </>
  );
}

/** Legend entries for the zones that ran: "Кромка: 1.8 px / допуск 1.5" with the reject mark. */
export function LineZonesLegend({ detectorResult }: { detectorResult?: DetectorResult }) {
  const entries = (["seam", "edge"] as const).flatMap((name) => {
    const zone = detectorResult?.lines?.[name];
    return zone ? [{ name, zone }] : [];
  });
  if (entries.length === 0) {
    return null;
  }

  return (
    <ul className="line-zones-legend">
      {entries.map(({ name, zone }) => (
        <li
          key={name}
          className="line-zones-legend__item"
          data-zone={name}
          data-reject={zone.reject ? "true" : undefined}
        >
          <strong>{ZONE_TITLES[name]}</strong>
          {zone.gap_reject
            ? ": линия не найдена на участке"
            : zone.found
              ? `: ${zone.max_dev.toFixed(2)} px, ${zone.ratio.toFixed(1)}× допуска`
              : ": линия не найдена"}
          {zone.reject ? " — повреждено" : " — норма"}
        </li>
      ))}
    </ul>
  );
}

function toPoints(points: ReadonlyArray<[number, number]>) {
  return points.map(([x, y]) => `${x},${y}`).join(" ");
}

/** Index ranges [from, to) of consecutive samples with the given state digit. */
function runs(states: string, digit: number): Array<[number, number]> {
  const wanted = String(digit);
  const result: Array<[number, number]> = [];
  let start = -1;
  for (let index = 0; index <= states.length; index += 1) {
    const inRun = index < states.length && states[index] === wanted;
    if (inRun && start < 0) {
      start = index;
    } else if (!inRun && start >= 0) {
      result.push([start, index]);
      start = -1;
    }
  }
  return result;
}

/** Samples of the measured edge whose deviation exceeds the tolerance at that position. */
function collectBadPoints(zone: DetectorLineZone): Array<[number, number]> {
  const { dev, tol, state, edge } = zone;
  if (!dev || !tol || !state || !edge) {
    return [];
  }
  const points: Array<[number, number]> = [];
  for (let index = 0; index < edge.length; index += 1) {
    if (state[index] === "0" && Math.abs(dev[index] ?? 0) > (tol[index] ?? Infinity)) {
      points.push(edge[index]);
    }
  }
  return points;
}

/** Closed outline of the search band (half-width in frame px) around the axis: one side forward, the other back. */
function bandOutline(
  axis: ReadonlyArray<[number, number]>,
  bandPx: number,
  frameWidth: number,
  frameHeight: number,
) {
  const left: Array<[number, number]> = [];
  const right: Array<[number, number]> = [];
  for (let index = 0; index < axis.length; index += 1) {
    const prev = axis[Math.max(0, index - 1)];
    const next = axis[Math.min(axis.length - 1, index + 1)];
    // the normal is taken in pixel space, then converted back to the normalised frame
    const dx = (next[0] - prev[0]) * frameWidth;
    const dy = (next[1] - prev[1]) * frameHeight;
    const length = Math.hypot(dx, dy) || 1;
    const nx = ((-dy / length) * bandPx) / frameWidth;
    const ny = ((dx / length) * bandPx) / frameHeight;
    left.push([axis[index][0] + nx, axis[index][1] + ny]);
    right.push([axis[index][0] - nx, axis[index][1] - ny]);
  }
  return toPoints([...left, ...right.reverse()]);
}
