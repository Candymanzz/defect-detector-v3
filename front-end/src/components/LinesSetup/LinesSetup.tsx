import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { ChangeEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent } from "react";
import { orchestratorApi } from "../../shared/api";
import type { LinesLastResult, LinesSettings, LineZoneSettings } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import { getReferenceImage, subscribeReferenceImages } from "../../shared/referenceImages";
import { Button } from "../../shared/ui/Button";
import { LinesDiagram } from "./LinesDiagram";
import {
  bandSide,
  nearestOnLine,
  nearestSegment,
  normToPx,
  orderedRoiPick,
  pathData,
  pxToNorm,
  skipAfterInsert,
  splineSegment,
} from "./linesGeometry";
import type { Pt } from "./linesGeometry";
import "./LinesSetup.css";

/**
 * Геометрия линий этикетки (cppAnalisSurface): две независимые зоны камеры — «Стык» (тонкая светлая линия) и «Кромка»
 * (граница светлое/тёмное). Линия задаётся контрольными точками на эталоне камеры; детектор проверяет её ровность на
 * каждом кадре. Вкладка «Диаграмма» показывает отклонение линий на последнем проверенном кадре.
 */

type ZoneKey = "seam" | "edge";
type EditMode = "pick" | "roi" | "skip";
type Tab = "lines" | "diagram";
type NumericParam = "band" | "tol_mid" | "tol_edge" | "edge_frac" | "smooth_win" | "min_contrast" | "max_gap";

const ZONES: ReadonlyArray<{ key: ZoneKey; title: string; hint: string; color: string; step: boolean }> = [
  { key: "seam", title: "Стык", hint: "тонкая светлая линия", color: "#00e5ff", step: false },
  { key: "edge", title: "Кромка", hint: "граница светлое/тёмное", color: "#b388ff", step: true },
];

// name: [min, max, step, decimals, label]
const PARAM_DEFS: Record<NumericParam, readonly [number, number, number, number, string]> = {
  band: [10, 80, 1, 0, "Полоса поиска (±), px"],
  tol_mid: [0.2, 5, 0.1, 1, "Допуск в середине, px"],
  tol_edge: [0.5, 15, 0.5, 1, "Допуск на краях линии, px"],
  edge_frac: [0, 0.4, 0.01, 2, "Длина краевой части (доля)"],
  smooth_win: [20, 200, 5, 0, "Окно сравнения, px"],
  min_contrast: [2, 40, 1, 0, "Мин. контраст, ур. серого"],
  max_gap: [0.05, 1, 0.05, 2, "Макс. разрыв линии (доля)"],
};
const PARAMS_BY_ZONE: Record<ZoneKey, readonly NumericParam[]> = {
  seam: ["band", "tol_mid", "tol_edge", "edge_frac"],
  edge: ["band", "tol_mid", "tol_edge", "edge_frac", "smooth_win", "min_contrast", "max_gap"],
};
const AUTOSAVE_DELAY_MS = 300;
const DIAGRAM_REFRESH_MS = 3000;

type Props = {
  initialCameraId: number | null;
  onClose: () => void;
};

type Edit = { zone: ZoneKey; mode: EditMode } | null;
type Drag = { zone: ZoneKey; index: number } | null;

export function LinesSetup({ initialCameraId, onClose }: Props) {
  const [cameraIds, setCameraIds] = useState<number[]>([]);
  const [cameraId, setCameraId] = useState<number | null>(initialCameraId);
  const [lines, setLines] = useState<LinesSettings | null>(null);
  const [defaults, setDefaults] = useState<LinesSettings | null>(null);
  const [edit, setEdit] = useState<Edit>(null);
  const [roiSelection, setRoiSelection] = useState<number[]>([]);
  const [drag, setDrag] = useState<Drag>(null);
  const [tab, setTab] = useState<Tab>("lines");
  const [status, setStatus] = useState<{ kind: "loading" | "saving" | "success" | "error"; text: string }>({
    kind: "loading",
    text: "Загрузка…",
  });
  const [last, setLast] = useState<LinesLastResult | null>(null);
  const [lastStatus, setLastStatus] = useState("");
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [wrapperWidth, setWrapperWidth] = useState(900);

  const linesRef = useRef<LinesSettings | null>(null);
  const editRef = useRef<Edit>(null);
  const cameraRef = useRef<number | null>(cameraId);
  const timers = useRef<Partial<Record<ZoneKey, number>>>({});
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    editRef.current = edit;
    cameraRef.current = cameraId;
  }, [edit, cameraId]);

  const reference = useSyncExternalStore(
    subscribeReferenceImages,
    () => (cameraId === null ? undefined : getReferenceImage(cameraId)),
    () => undefined,
  );
  const frameW = reference?.frame.width ?? 0;
  const frameH = reference?.frame.height ?? 0;
  const roiPx = useMemo<Pt[]>(
    () => (reference && frameW > 1 ? reference.roiPoints.map((p) => normToPx([p.x, p.y], frameW, frameH)) : []),
    [reference, frameW, frameH],
  );

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => {
    const previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousBodyOverflow;
    };
  }, []);

  useEffect(() => {
    let active = true;
    orchestratorApi
      .listCameras()
      .then(({ cameras }) => {
        if (!active) return;
        const ids = [...new Set(cameras)].sort((a, b) => a - b);
        setCameraIds(ids);
        setCameraId((current) => (current !== null && ids.includes(current) ? current : (ids[0] ?? null)));
      })
      .catch((error) => active && setStatus({ kind: "error", text: errorMessage(error) }));
    return () => {
      active = false;
    };
  }, []);

  // Pending autosaves are sent before the camera changes or the window closes.
  const flushNow = useCallback((zone: ZoneKey, forCamera: number | null) => {
    const timer = timers.current[zone];
    if (timer !== undefined) {
      window.clearTimeout(timer);
      delete timers.current[zone];
    }
    const current = linesRef.current;
    const editing = editRef.current;
    if (forCamera === null || !current) return Promise.resolve();
    const z = current[zone];
    // an unfinished line (one point, picking, choosing ROI vertices) is only a draft
    if (z.pts.length === 1 || (editing && editing.zone === zone && editing.mode !== "skip")) return Promise.resolve();
    setStatus({ kind: "saving", text: "Сохранение…" });
    return orchestratorApi
      .setCameraLines(forCamera, { [zone]: { ...z, enabled: z.enabled && z.pts.length >= 2 } })
      .then(() => {
        if (cameraRef.current === forCamera) setStatus({ kind: "success", text: "Сохранено" });
      })
      .catch((error) => setStatus({ kind: "error", text: errorMessage(error) }));
  }, []);

  const flushAll = useCallback(
    (forCamera: number | null) => {
      for (const zone of ["seam", "edge"] as const) {
        if (timers.current[zone] !== undefined) void flushNow(zone, forCamera);
      }
    },
    [flushNow],
  );

  useEffect(() => {
    if (cameraId === null) return;
    let active = true;
    orchestratorApi
      .getCameraLines(cameraId)
      .then((response) => {
        if (!active) return;
        linesRef.current = response.lines;
        setLines(response.lines);
        setDefaults(response.defaults);
        setStatus({ kind: "success", text: `Камера ${cameraId}: линии загружены` });
      })
      .catch((error) => active && setStatus({ kind: "error", text: errorMessage(error) }));
    return () => {
      active = false;
      flushAll(cameraId);
    };
  }, [cameraId, flushAll]);

  // Switching the camera: pending edits go to the old camera first, then the editor starts from scratch.
  const selectCamera = (next: number) => {
    flushAll(cameraId);
    linesRef.current = null;
    editRef.current = null;
    setLines(null);
    setEdit(null);
    setRoiSelection([]);
    setLast(null);
    setStatus({ kind: "loading", text: "Загрузка…" });
    setCameraId(next);
  };

  const scheduleSave = (zone: ZoneKey) => {
    const timer = timers.current[zone];
    if (timer !== undefined) window.clearTimeout(timer);
    const forCamera = cameraRef.current;
    timers.current[zone] = window.setTimeout(() => void flushNow(zone, forCamera), AUTOSAVE_DELAY_MS);
  };

  const update = (zone: ZoneKey, change: (z: LineZoneSettings) => LineZoneSettings, save = true) => {
    const current = linesRef.current;
    if (!current) return;
    const next: LinesSettings = { ...current, [zone]: change(current[zone]) };
    linesRef.current = next;
    setLines(next);
    if (save) scheduleSave(zone);
  };

  const startEdit = (zone: ZoneKey, mode: EditMode) => {
    if (edit && edit.zone !== zone) finishEdit(edit.zone);
    if (mode === "pick") update(zone, (z) => ({ ...z, pts: [], skip: [], enabled: false }), false);
    if (mode === "roi") setRoiSelection([]);
    setEdit({ zone, mode });
  };

  const finishEdit = (zone: ZoneKey) => {
    const current = linesRef.current;
    if (!current) return;
    const mode = editRef.current?.mode;
    if (mode === "roi") {
      if (roiSelection.length < 2) {
        setStatus({ kind: "error", text: "Выберите минимум 2 вершины ROI" });
        return;
      }
      const ordered = orderedRoiPick(roiSelection, roiPx.length);
      editRef.current = null;
      update(zone, (z) => ({
        ...z,
        pts: ordered.map((i) => pxToNorm(roiPx[i], frameW, frameH)),
        skip: [],
        enabled: true,
      }));
    } else if (mode === "pick") {
      if (current[zone].pts.length < 2) {
        setStatus({ kind: "error", text: "Нужно минимум 2 точки" });
        return;
      }
      editRef.current = null;
      update(zone, (z) => ({ ...z, enabled: true }));
    } else {
      editRef.current = null;
      scheduleSave(zone);
    }
    setEdit(null);
    setRoiSelection([]);
  };

  const clearZone = (zone: ZoneKey) => {
    if (edit?.zone === zone) {
      editRef.current = null;
      setEdit(null);
      setRoiSelection([]);
    }
    update(zone, (z) => ({ ...z, pts: [], skip: [], enabled: false }));
  };

  const resetParams = (zone: ZoneKey) => {
    if (!defaults) return;
    const d = defaults[zone];
    update(zone, (z) => ({
      ...z,
      band: d.band,
      tol_mid: d.tol_mid,
      tol_edge: d.tol_edge,
      edge_frac: d.edge_frac,
      smooth_win: d.smooth_win,
      min_contrast: d.min_contrast,
      max_gap: d.max_gap,
      polarity: d.polarity,
    }));
  };

  // ---- pointer handling on the frame ----

  const k = frameW > 0 ? frameW / Math.max(1, wrapperWidth) : 1;   // frame px per screen px
  const hit = 11 * k;

  useLayoutEffect(() => {
    const node = wrapperRef.current;
    if (!node) return;
    const observer = new ResizeObserver(() => setWrapperWidth(node.clientWidth));
    observer.observe(node);
    setWrapperWidth(node.clientWidth);
    return () => observer.disconnect();
  }, [reference?.imageUrl, tab]);

  const framePoint = (event: { clientX: number; clientY: number }, target: Element): Pt => {
    const rect = target.getBoundingClientRect();
    return [
      Math.max(0, Math.min(frameW - 1, ((event.clientX - rect.left) * frameW) / rect.width)),
      Math.max(0, Math.min(frameH - 1, ((event.clientY - rect.top) * frameH) / rect.height)),
    ];
  };

  const zonePts = (zone: ZoneKey): Pt[] =>
    (linesRef.current?.[zone].pts ?? []).map((p) => normToPx(p, frameW, frameH));

  const onPointerDown = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (event.button !== 0 || !linesRef.current || frameW <= 1) return;
    const target = event.currentTarget;
    const p = framePoint(event, target);

    if (event.shiftKey && !edit) {                       // Shift+click on a line: add a control point there
      for (const { key } of ZONES) {
        const found = nearestOnLine(zonePts(key), p, hit * 2.5);
        if (!found) continue;
        const n = pxToNorm([Math.round(found.point[0]), Math.round(found.point[1])], frameW, frameH);
        update(key, (z) => {
          const pts = z.pts.slice();
          pts.splice(found.segment + 1, 0, n);
          return { ...z, pts, skip: skipAfterInsert(z.skip, found.segment) };
        });
        return;
      }
    }

    if (edit) {
      const zone = edit.zone;
      if (edit.mode === "pick") {
        update(zone, (z) => ({ ...z, pts: [...z.pts, pxToNorm([Math.round(p[0]), Math.round(p[1])], frameW, frameH)] }), false);
      } else if (edit.mode === "roi") {
        let best = -1;
        let bestDist = hit;
        roiPx.forEach((q, i) => {
          const d = Math.hypot(q[0] - p[0], q[1] - p[1]);
          if (d <= bestDist) {
            bestDist = d;
            best = i;
          }
        });
        if (best >= 0) {
          setRoiSelection((sel) => (sel.includes(best) ? sel.filter((i) => i !== best) : [...sel, best]));
        }
      } else {
        const segment = nearestSegment(zonePts(zone), p, hit * 2.5);
        if (segment >= 0) {
          update(zone, (z) => ({
            ...z,
            skip: z.skip.includes(segment) ? z.skip.filter((s) => s !== segment) : [...z.skip, segment],
          }));
        }
      }
      return;
    }

    for (const { key } of ZONES) {                       // drag a control point
      const pts = zonePts(key);
      let best = -1;
      let bestDist = hit;
      pts.forEach((q, i) => {
        const d = Math.hypot(q[0] - p[0], q[1] - p[1]);
        if (d <= bestDist) {
          bestDist = d;
          best = i;
        }
      });
      if (best >= 0) {
        target.setPointerCapture(event.pointerId);
        setDrag({ zone: key, index: best });
        return;
      }
    }
  };

  const onPointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (!drag) return;
    const p = framePoint(event, event.currentTarget);
    const n = pxToNorm([Math.round(p[0]), Math.round(p[1])], frameW, frameH);
    update(drag.zone, (z) => ({ ...z, pts: z.pts.map((q, i) => (i === drag.index ? n : q)) }), false);
  };

  const onPointerUp = () => {
    if (!drag) return;
    scheduleSave(drag.zone);
    setDrag(null);
  };

  const onContextMenu = (event: ReactMouseEvent<SVGSVGElement>) => {
    event.preventDefault();
    if (edit || !linesRef.current) return;
    const p = framePoint(event, event.currentTarget);
    for (const { key } of ZONES) {
      const pts = zonePts(key);
      let best = -1;
      let bestDist = hit;
      pts.forEach((q, i) => {
        const d = Math.hypot(q[0] - p[0], q[1] - p[1]);
        if (d <= bestDist) {
          bestDist = d;
          best = i;
        }
      });
      if (best >= 0) {
        update(key, (z) => {
          const next = z.pts.filter((_, i) => i !== best);
          return { ...z, pts: next, skip: [], enabled: z.enabled && next.length >= 2 };
        });
        return;
      }
    }
  };

  // ---- diagram ----

  const loadLast = useCallback(async () => {
    if (cameraId === null) return;
    try {
      const response = await orchestratorApi.getCameraLinesLast(cameraId);
      setLast(response.last);
      setLastStatus(
        response.last
          ? `Кадр от ${new Date(response.last.ts_ms).toLocaleTimeString("ru-RU")}`
          : "Камера ещё не проверяла кадры с включённой геометрией линий",
      );
    } catch (error) {
      setLastStatus(errorMessage(error));
    }
  }, [cameraId]);

  useEffect(() => {
    if (tab !== "diagram") return;
    const first = window.setTimeout(() => void loadLast(), 0);
    const timer = autoRefresh ? window.setInterval(() => void loadLast(), DIAGRAM_REFRESH_MS) : undefined;
    return () => {
      window.clearTimeout(first);
      if (timer !== undefined) window.clearInterval(timer);
    };
  }, [tab, autoRefresh, loadLast]);

  const close = () => {
    flushAll(cameraId);
    onClose();
  };

  const busy = status.kind === "loading";

  return (
    <div className="lines-setup" role="dialog" aria-modal="true" aria-label="Линии стыка и кромки">
      <div className="lines-setup__modal">
        <header className="lines-setup__header">
          <div>
            <h2>Линии: стык и кромка</h2>
            <span>Проверка ровности линий этикетки: две независимые зоны на камеру</span>
          </div>
          <label className="lines-setup__camera">
            Камера
            <select
              value={cameraId ?? ""}
              onChange={(event: ChangeEvent<HTMLSelectElement>) => selectCamera(Number(event.target.value))}
            >
              {cameraIds.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="lines-setup__close" aria-label="Закрыть" onClick={close}>
            ×
          </button>
        </header>

        <nav className="lines-setup__tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === "lines"} onClick={() => setTab("lines")}>
            Линии
          </button>
          <button type="button" role="tab" aria-selected={tab === "diagram"} onClick={() => setTab("diagram")}>
            Диаграмма
          </button>
          <span className="lines-setup__status" data-kind={status.kind} aria-live="polite">
            {status.text}
          </span>
        </nav>

        {tab === "lines" && (
          <div className="lines-setup__body">
            <section className="lines-setup__canvas">
              {reference && frameW > 1 ? (
                <div
                  className="lines-setup__frame"
                  ref={wrapperRef}
                  style={{
                    aspectRatio: `${frameW} / ${frameH}`,
                    // the whole frame stays visible: limited by the width of the column and by the window height
                    width: `min(100%, calc((100vh - 250px) * ${frameW / frameH}))`,
                  }}
                >
                  <img src={reference.imageUrl} alt={`Эталон камеры ${cameraId}`} draggable={false} />
                  <svg
                    viewBox={`0 0 ${frameW} ${frameH}`}
                    data-editing={edit ? edit.mode : ""}
                    onPointerDown={onPointerDown}
                    onPointerMove={onPointerMove}
                    onPointerUp={onPointerUp}
                    onPointerCancel={onPointerUp}
                    onContextMenu={onContextMenu}
                  >
                    {roiPx.length >= 3 && (
                      <path d={`${pathData(roiPx)}Z`} className="lines-setup__roi" strokeWidth={1.2 * k} />
                    )}
                    {edit?.mode === "roi" &&
                      roiPx.map((q, i) => (
                        <circle
                          key={i}
                          cx={q[0]}
                          cy={q[1]}
                          r={(roiSelection.includes(i) ? 6 : 4) * k}
                          className={roiSelection.includes(i) ? "lines-setup__roi-pick lines-setup__roi-pick--on" : "lines-setup__roi-pick"}
                          strokeWidth={1.2 * k}
                        />
                      ))}
                    {lines &&
                      ZONES.map(({ key, color }) => (
                        <ZoneOverlay
                          key={key}
                          zone={lines[key]}
                          color={color}
                          k={k}
                          width={frameW}
                          height={frameH}
                          dragIndex={drag?.zone === key ? drag.index : -1}
                        />
                      ))}
                  </svg>
                </div>
              ) : (
                <p className="lines-setup__empty">
                  Для камеры {cameraId ?? "—"} не задан эталон. Задайте эталон кнопкой «Эталон» — линии рисуются на его кадре.
                </p>
              )}
              <p className="lines-setup__hint">
                Тяни точку мышью, ПКМ — удалить точку, Shift+клик по линии — добавить точку. Белые полосы — область поиска
                линии (±&nbsp;полоса), оранжевый пунктир — участок, исключённый из проверки.
              </p>
            </section>

            <section className="lines-setup__zones">
              {lines &&
                ZONES.map((zoneInfo) => {
                  const zone = lines[zoneInfo.key];
                  const editing = edit?.zone === zoneInfo.key ? edit.mode : null;
                  return (
                    <ZoneCard
                      key={zoneInfo.key}
                      info={zoneInfo}
                      zone={zone}
                      editing={editing}
                      roiSelected={roiSelection.length}
                      hasRoi={roiPx.length >= 3}
                      hasFrame={Boolean(reference)}
                      busy={busy}
                      onChange={(change) => update(zoneInfo.key, change)}
                      onEdit={(mode) => startEdit(zoneInfo.key, mode)}
                      onDone={() => finishEdit(zoneInfo.key)}
                      onClear={() => clearZone(zoneInfo.key)}
                      onReset={() => resetParams(zoneInfo.key)}
                      onEnableDenied={() => setStatus({ kind: "error", text: "Сначала задайте линию на кадре" })}
                    />
                  );
                })}
            </section>
          </div>
        )}

        {tab === "diagram" && (
          <div className="lines-setup__diagram">
            <div className="lines-setup__diagram-bar">
              <Button type="button" onClick={() => void loadLast()}>
                Обновить
              </Button>
              <label>
                <input
                  type="checkbox"
                  checked={autoRefresh}
                  onChange={(event) => setAutoRefresh(event.target.checked)}
                />
                автообновление
              </label>
              <span>{lastStatus}</span>
            </div>
            {last && (
              <>
                <p className="lines-setup__diagram-verdict" data-reject={last.reject ? "true" : "false"}>
                  {last.reject ? "Кадр забракован" : "Кадр годен"} · {last.frame_width}×{last.frame_height}
                </p>
                {last.seam && <LinesDiagram title="Стык" result={last.seam} />}
                {last.edge && <LinesDiagram title="Кромка" result={last.edge} />}
                {!last.seam && !last.edge && <p className="lines-setup__empty">Зоны выключены или линия не задана.</p>}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

type OverlayProps = {
  zone: LineZoneSettings;
  color: string;
  k: number;
  width: number;
  height: number;
  dragIndex: number;
};

function ZoneOverlay({ zone, color, k, width, height, dragIndex }: OverlayProps) {
  const pts = zone.pts.map((p) => normToPx(p, width, height));
  if (pts.length === 0) return null;
  const stroke = zone.enabled ? color : `${color}73`;
  const segments = [];
  for (let s = 0; s + 1 < pts.length; s++) {
    const curve = splineSegment(pts, s);
    const skipped = zone.skip.includes(s);
    segments.push(
      <g key={s}>
        {!skipped &&
          ([1, -1] as const).map((sign) => (
            <path
              key={sign}
              d={pathData(bandSide(curve, zone.band, sign))}
              className="lines-setup__band"
              strokeWidth={1.2 * k}
            />
          ))}
        <path
          d={pathData(curve)}
          className="lines-setup__line"
          stroke={skipped ? "#ff9800" : stroke}
          strokeDasharray={skipped ? `${6 * k} ${5 * k}` : undefined}
          strokeWidth={1.8 * k}
        />
      </g>,
    );
  }
  return (
    <g>
      {segments}
      {pts.map((p, i) => (
        <circle
          key={i}
          cx={p[0]}
          cy={p[1]}
          r={5 * k}
          fill={i === dragIndex ? "#fff" : color}
          className="lines-setup__point"
          strokeWidth={1.5 * k}
        />
      ))}
    </g>
  );
}

type CardProps = {
  info: (typeof ZONES)[number];
  zone: LineZoneSettings;
  editing: EditMode | null;
  roiSelected: number;
  hasRoi: boolean;
  hasFrame: boolean;
  busy: boolean;
  onChange: (change: (z: LineZoneSettings) => LineZoneSettings) => void;
  onEdit: (mode: EditMode) => void;
  onDone: () => void;
  onClear: () => void;
  onReset: () => void;
  onEnableDenied: () => void;
};

function ZoneCard({
  info,
  zone,
  editing,
  roiSelected,
  hasRoi,
  hasFrame,
  busy,
  onChange,
  onEdit,
  onDone,
  onClear,
  onReset,
  onEnableDenied,
}: CardProps) {
  const drawing = editing === "pick" || editing === "roi";
  const n = zone.pts.length;
  const infoText =
    editing === "skip"
      ? `кликайте по участкам линии, которые не проверять (исключено: ${zone.skip.length}); повторный клик возвращает участок`
      : editing === "roi"
        ? `кликайте по вершинам ROI вдоль линии (выбрано: ${roiSelected}), затем «Готово»`
        : editing === "pick"
          ? `кликайте по линии на кадре (точек: ${n}), затем «Готово»`
          : n < 2
            ? "линия не задана (нужно минимум 2 точки)"
            : `точек: ${n}` + (info.step ? "; для кромки ставьте больше точек вдоль изгиба" : "");

  return (
    <div className="lines-setup__card" style={{ borderColor: info.color }}>
      <h3 style={{ color: info.color }}>
        {info.title} <small>— {info.hint}</small>
      </h3>
      <label className="lines-setup__check">
        <input
          type="checkbox"
          checked={zone.enabled}
          disabled={busy}
          onChange={(event) => {
            if (event.target.checked && n < 2) {
              onEnableDenied();
              return;
            }
            onChange((z) => ({ ...z, enabled: event.target.checked }));
          }}
        />
        проверять геометрию
      </label>
      {info.step && (
        <label className="lines-setup__select">
          Яркая сторона
          <select
            value={zone.polarity}
            disabled={busy}
            onChange={(event) => onChange((z) => ({ ...z, polarity: Number(event.target.value) as -1 | 0 | 1 }))}
          >
            <option value={0}>любая</option>
            <option value={1}>справа/снизу от линии</option>
            <option value={-1}>слева/сверху от линии</option>
          </select>
        </label>
      )}
      <div className="lines-setup__buttons">
        {!drawing && (
          <>
            <button type="button" disabled={busy || !hasRoi || !hasFrame} onClick={() => onEdit("roi")}>
              Взять из точек ROI
            </button>
            <button type="button" disabled={busy || !hasFrame} onClick={() => onEdit("pick")}>
              Задать вручную
            </button>
          </>
        )}
        {(drawing || editing === "skip") && (
          <button type="button" className="lines-setup__primary" onClick={onDone}>
            Готово
          </button>
        )}
        {!drawing && n >= 2 && editing !== "skip" && (
          <button type="button" onClick={() => onEdit("skip")}>
            Исключить участки
          </button>
        )}
        <button type="button" disabled={busy} onClick={onReset} title="Допуски и пороги вернутся к значениям по умолчанию">
          {info.step ? "Значения для кромки" : "Значения по умолчанию"}
        </button>
        <button type="button" disabled={busy} onClick={onClear}>
          Сбросить линию
        </button>
      </div>
      <div className="lines-setup__info">{infoText}</div>
      {PARAMS_BY_ZONE[info.key].map((name) => {
        const [min, max, step, decimals, label] = PARAM_DEFS[name];
        const value = zone[name];
        return (
          <label className="lines-setup__param" key={name}>
            <span>
              {label} <b>{value.toFixed(decimals)}</b>
            </span>
            <input
              type="range"
              min={min}
              max={max}
              step={step}
              value={value}
              disabled={busy}
              onChange={(event) => {
                const next = Number(event.target.value);
                onChange((z) => ({ ...z, [name]: next }));
              }}
            />
          </label>
        );
      })}
    </div>
  );
}
