import type { LineZoneResult } from "../../shared/api";

/**
 * Диаграмма отклонения линии: по горизонтали позиция вдоль линии, по вертикали отклонение от опорной линии в px.
 * Жёлтая рамка — допуск, зелёные точки — в допуске, красные — выход за допуск, пурпурные отметки внизу — линия
 * не найдена, оранжевые вверху — участок исключён из проверки.
 */

const W = 1000;
const H = 190;
const MARGIN = 10;
const TOP = 22;

type Props = {
  title: string;
  result: LineZoneResult;
};

export function LinesDiagram({ title, result }: Props) {
  const dev = result.dev ?? [];
  const tol = result.tol ?? [];
  const state = result.state ?? "";
  const n = dev.length;
  if (n < 2) {
    return (
      <figure className="lines-diagram">
        <figcaption>{title}</figcaption>
        <div className="lines-diagram__empty">Нет измерений: линия не задана или кадр не проверялся.</div>
      </figure>
    );
  }

  const tolMax = Math.max(0.5, ...tol);
  const range = Math.max(3 * tolMax, 3);
  const mid = (TOP + H - 10) / 2;
  const scale = (H - 10 - TOP) / 2 / range;
  const x = (i: number) => MARGIN + ((W - 2 * MARGIN) * i) / (n - 1);
  const y = (d: number) => mid - Math.max(-range, Math.min(range, d)) * scale;
  const over = (i: number) => Math.abs(dev[i]) > (tol[i] ?? Infinity);

  let worst = -1;
  for (let i = 0; i < n; i++) {
    if (state[i] === "0" && (worst < 0 || Math.abs(dev[i]) > Math.abs(dev[worst]))) worst = i;
  }
  const tolUpper = tol.map((t, i) => `${i ? "L" : "M"}${x(i).toFixed(1)} ${y(t).toFixed(1)}`).join("");
  const tolLower = tol.map((t, i) => `${i ? "L" : "M"}${x(i).toFixed(1)} ${y(-t).toFixed(1)}`).join("");

  return (
    <figure className="lines-diagram" data-reject={result.reject ? "true" : "false"}>
      <figcaption>
        <strong>{title}</strong>
        <span>
          {result.reject ? "БРАК" : "норма"} · макс. {result.max_dev.toFixed(2)} px · {result.ratio.toFixed(2)}× допуска ·
          шум {result.noise.toFixed(2)} px · найдено {(result.coverage * 100).toFixed(0)}%
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Отклонение линии: ${title}`}>
        <rect x={0} y={0} width={W} height={H} className="lines-diagram__bg" />
        <line x1={MARGIN} x2={W - MARGIN} y1={mid} y2={mid} className="lines-diagram__axis" />
        <path d={tolUpper} className="lines-diagram__tolerance" />
        <path d={tolLower} className="lines-diagram__tolerance" />
        {dev.map((d, i) => {
          const s = state[i];
          if (s === "1") return <line key={i} x1={x(i)} x2={x(i)} y1={TOP - 4} y2={TOP + 4} className="lines-diagram__skipped" />;
          if (s === "3") return <line key={i} x1={x(i)} x2={x(i)} y1={H - 8} y2={H - 1} className="lines-diagram__missing" />;
          if (s !== "0") return null;
          return <circle key={i} cx={x(i)} cy={y(d)} r={1.8} className={over(i) ? "lines-diagram__bad" : "lines-diagram__ok"} />;
        })}
        <text x={MARGIN} y={13} className="lines-diagram__label">
          отклонение, px (жёлтое — допуск, масштаб ±{range.toFixed(1)}) · пурпур — не найдено, оранжевый — исключено
        </text>
        {worst >= 0 && (
          <g>
            <line x1={x(worst)} x2={x(worst)} y1={TOP} y2={H - 8} className="lines-diagram__worst" />
            <text
              x={Math.min(x(worst) + 5, W - 110)}
              y={TOP + 12}
              className="lines-diagram__label lines-diagram__label--strong"
            >
              max {Math.abs(dev[worst]).toFixed(2)} px
            </text>
          </g>
        )}
      </svg>
    </figure>
  );
}
