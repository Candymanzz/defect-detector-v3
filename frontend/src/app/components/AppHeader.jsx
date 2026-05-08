export function AppHeader() {
  return (
    <header className="rounded-2xl border border-amber-500/30 bg-panel px-6 py-4 shadow-2xl shadow-black/20">
      <h1 className="text-2xl font-semibold tracking-wide text-amber-300">
        QC Dashboard - Bucket Inspection
      </h1>
      <p className="text-sm text-slate-400">
        Выравнивание, карта разницы и оценка аномалий в реальном времени.
      </p>
    </header>
  );
}
