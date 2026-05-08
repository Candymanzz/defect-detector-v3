export function FpRecheckStats({ recheckStats }) {
  return (
    <div className="mt-2 text-xs text-slate-300">
      <div>Recheck зон в последней проверке: {recheckStats.count}</div>
      <div>
        Raw score: {recheckStats.rawScore.toFixed(3)} | Коррекция: -
        {Math.max(0, recheckStats.adjustment).toFixed(3)}
      </div>
    </div>
  );
}
