export function AnomalyScoreMeter({ score, threshold }) {
  return (
    <div className="rounded-xl border border-slate-700 bg-panelSoft p-3">
      <div className="mb-2 flex justify-between text-sm text-slate-300">
        <span>Anomaly Score</span>
        <span>{score.toFixed(3)}</span>
      </div>
      <div className="h-3 overflow-hidden rounded bg-slate-700">
        <div
          className={`h-full ${score >= threshold ? "bg-red-500" : "bg-green-500"}`}
          style={{ width: `${Math.min(100, Math.round(score * 100))}%` }}
        />
      </div>
    </div>
  );
}
