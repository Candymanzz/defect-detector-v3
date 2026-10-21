import { useRef, useState, useSyncExternalStore } from "react";
import type { ChangeEvent, PointerEvent } from "react";
import type { SeamSettings } from "../../shared/api";
import { getReferenceImageUrl, subscribeReferenceImages } from "../../shared/referenceImages";
import "./SeamSettingsSection.css";

/**
 * Контроль стыка этикетки (cppAnalisSurface): оператор отмечает линию стыка на эталоне камеры,
 * детектор измеряет её прямоту и бракует излом. Допуск в середине строгий, на концах стыка мягкий.
 */

type Pt = [number, number];
type Pick = "idle" | "first" | "second";

type SliderField = {
  name: "tol_mid" | "tol_edge" | "edge_frac" | "band";
  label: string;
  hint: string;
  min: number;
  max: number;
  step: number;
  factor: number;
  unit: string;
  decimals: number;
};

const SLIDERS: readonly SliderField[] = [
  {
    name: "tol_mid", label: "Допуск в середине стыка",
    hint: "Насколько линия стыка может отклоняться от плавной кривой в средней части. Меньше — строже к изломам.",
    min: 0.2, max: 5, step: 0.1, factor: 1, unit: "px", decimals: 1,
  },
  {
    name: "tol_edge", label: "Допуск на концах стыка",
    hint: "То же для краевых частей стыка: здесь небольшие отклонения считаются нормой.",
    min: 0.5, max: 15, step: 0.5, factor: 1, unit: "px", decimals: 1,
  },
  {
    name: "edge_frac", label: "Длина краевой части",
    hint: "Какая доля длины стыка с каждого конца считается краем (мягкий допуск).",
    min: 0, max: 0.4, step: 0.01, factor: 100, unit: "%", decimals: 0,
  },
  {
    name: "band", label: "Полоса поиска (±)",
    hint: "Насколько далеко от нарисованной линии искать стык. Нужен запас на положение изделия.",
    min: 10, max: 80, step: 1, factor: 1, unit: "px", decimals: 0,
  },
];

type Props = {
  cameraId: number;
  seam: SeamSettings;
  disabled?: boolean;
  onChange: (next: SeamSettings) => void;
};

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

export function SeamSettingsSection({ cameraId, seam, disabled = false, onChange }: Props) {
  const imageUrl = useSyncExternalStore(
    subscribeReferenceImages,
    () => getReferenceImageUrl(cameraId),
    () => undefined,
  );
  const wrapRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<number | null>(null);
  const [pick, setPick] = useState<Pick>("idle");
  const [size, setSize] = useState({ w: 1224, h: 1024 });
  const hasLine = Math.abs(seam.p1[0] - seam.p0[0]) + Math.abs(seam.p1[1] - seam.p0[1]) > 1e-6;

  const toNorm = (event: PointerEvent): Pt => {
    const rect = wrapRef.current!.getBoundingClientRect();
    return [clamp01((event.clientX - rect.left) / rect.width), clamp01((event.clientY - rect.top) / rect.height)];
  };

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (disabled || event.button !== 0) return;
    const p = toNorm(event);
    if (pick === "first") {
      onChange({ ...seam, p0: p, p1: p });
      setPick("second");
      return;
    }
    if (pick === "second") {
      onChange({ ...seam, p1: p });
      setPick("idle");
      return;
    }
    if (!hasLine) return;
    const rect = wrapRef.current!.getBoundingClientRect();
    const dist = (a: Pt) => Math.hypot((a[0] - p[0]) * rect.width, (a[1] - p[1]) * rect.height);
    const d0 = dist(seam.p0);
    const d1 = dist(seam.p1);
    if (Math.min(d0, d1) <= 14) {
      dragRef.current = d0 <= d1 ? 0 : 1;
      event.currentTarget.setPointerCapture(event.pointerId);
    }
  };

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (disabled) return;
    if (pick === "second") {
      onChange({ ...seam, p1: toNorm(event) });
      return;
    }
    if (dragRef.current === null) return;
    const p = toNorm(event);
    onChange(dragRef.current === 0 ? { ...seam, p0: p } : { ...seam, p1: p });
  };

  const onPointerUp = () => {
    dragRef.current = null;
  };

  const toggle = (event: ChangeEvent<HTMLInputElement>) => {
    if (event.target.checked && !hasLine) {
      setPick("first");
    }
    onChange({ ...seam, enabled: event.target.checked });
  };

  // search band around the p0-p1 line: computed in frame pixels, drawn in 0..1 coordinates
  const band = (() => {
    if (!hasLine) return "";
    const ax = seam.p0[0] * size.w;
    const ay = seam.p0[1] * size.h;
    const bx = seam.p1[0] * size.w;
    const by = seam.p1[1] * size.h;
    const len = Math.hypot(bx - ax, by - ay) || 1;
    const nx = (-(by - ay) / len) * seam.band;
    const ny = ((bx - ax) / len) * seam.band;
    return [[ax + nx, ay + ny], [bx + nx, by + ny], [bx - nx, by - ny], [ax - nx, ay - ny]]
      .map(([x, y]) => `${x / size.w},${y / size.h}`)
      .join(" ");
  })();

  return (
    <div className="seam-settings">
      <label className="seam-settings__toggle">
        <input type="checkbox" checked={seam.enabled} disabled={disabled} onChange={toggle} />
        <strong>Контроль стыка этикетки</strong>
      </label>
      <small className="seam-settings__hint">
        Проверяет, что линия стыка ровная. Изломы в середине — брак, на концах допускаются в пределах допуска.
      </small>

      <div className="seam-settings__toolbar">
        <button type="button" disabled={disabled || !imageUrl} onClick={() => setPick("first")}>
          {hasLine ? "Задать линию заново" : "Задать линию стыка"}
        </button>
        <span>
          {pick === "first"
            ? "Кликните по началу стыка (верхний конец)"
            : pick === "second"
              ? "Кликните по концу стыка (нижний конец)"
              : hasLine
                ? "Концы линии можно перетаскивать"
                : "Линия стыка не задана"}
        </span>
      </div>

      {imageUrl ? (
        <div
          ref={wrapRef}
          className="seam-settings__canvas"
          data-picking={pick !== "idle"}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
        >
          <img
            src={imageUrl}
            alt={`Эталон камеры ${cameraId}`}
            draggable={false}
            onLoad={(event) =>
              setSize({ w: event.currentTarget.naturalWidth || 1224, h: event.currentTarget.naturalHeight || 1024 })}
          />
          {hasLine && (
            <svg viewBox="0 0 1 1" preserveAspectRatio="none" aria-hidden="true">
              <polygon points={band} className="seam-settings__band" vectorEffect="non-scaling-stroke" />
              <line
                x1={seam.p0[0]}
                y1={seam.p0[1]}
                x2={seam.p1[0]}
                y2={seam.p1[1]}
                className="seam-settings__line"
                vectorEffect="non-scaling-stroke"
              />
            </svg>
          )}
          {hasLine && [seam.p0, seam.p1].map((p, i) => (
            <span
              key={i}
              className="seam-settings__handle"
              style={{ left: `${p[0] * 100}%`, top: `${p[1] * 100}%` }}
            />
          ))}
        </div>
      ) : (
        <div className="seam-settings__empty">
          Эталон камеры ещё не получен — линию стыка можно задать после съёмки эталона.
        </div>
      )}

      <div className="analysis-presets__fields seam-settings__fields">
        {SLIDERS.map((field) => {
          const value = seam[field.name];
          return (
            <label className="analysis-presets__field" key={field.name}>
              <span>
                <strong>{field.label}</strong>
                <output>{(value * field.factor).toFixed(field.decimals)} {field.unit}</output>
              </span>
              <input
                type="range"
                min={field.min}
                max={field.max}
                step={field.step}
                value={value}
                disabled={disabled}
                onChange={(event: ChangeEvent<HTMLInputElement>) =>
                  onChange({ ...seam, [field.name]: Number(event.target.value) })}
              />
              <small>{field.hint}</small>
            </label>
          );
        })}
      </div>
    </div>
  );
}
