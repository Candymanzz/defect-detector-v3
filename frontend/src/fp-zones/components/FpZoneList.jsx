export function FpZoneList({ zones, lastRecheckedZoneIds, busy, onDelete }) {
  if (zones.length === 0) return null;

  return (
    <div className="mt-2 max-h-24 space-y-1 overflow-y-auto rounded border border-slate-700 bg-slate-900/60 p-2 text-xs">
      {zones.map((zone) => (
        <div key={zone.id} className="flex items-center justify-between gap-2">
          <span
            className={
              lastRecheckedZoneIds.includes(zone.id) ? "text-emerald-300" : "text-slate-300"
            }
          >
            {zone.id.slice(0, 8)}...
          </span>
          <button
            onClick={() => onDelete(zone.id)}
            disabled={busy}
            className="rounded bg-rose-600 px-2 py-0.5 text-white disabled:opacity-60"
          >
            Удалить
          </button>
        </div>
      ))}
    </div>
  );
}
