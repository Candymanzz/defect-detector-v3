import { memo } from "react";

export const InspectionLogTable = memo(function InspectionLogTable({ logs }) {
  return (
    <section className="rounded-2xl border border-slate-700 bg-panel p-4">
      <h2 className="mb-3 text-lg font-medium">Лог проверок</h2>
      <div className="max-h-60 overflow-y-auto rounded-xl border border-slate-700">
        <table className="w-full border-collapse text-sm">
          <thead className="bg-slate-900 text-slate-300">
            <tr>
              <th className="px-3 py-2 text-left">Время</th>
              <th className="px-3 py-2 text-left">Результат</th>
              <th className="px-3 py-2 text-left">Score</th>
              <th className="px-3 py-2 text-left">FP recheck</th>
              <th className="px-3 py-2 text-left">Длительность</th>
            </tr>
          </thead>
          <tbody>
            {logs.length === 0 ? (
              <tr>
                <td className="px-3 py-4 text-slate-500" colSpan={5}>
                  Пока нет проверок
                </td>
              </tr>
            ) : (
              logs.map((entry, index) => (
                <tr key={`${entry.ts}-${index}`} className="border-t border-slate-800">
                  <td className="px-3 py-2">{entry.ts}</td>
                  <td
                    className={`px-3 py-2 ${entry.result === "БРАК" ? "text-red-400" : "text-green-400"}`}
                  >
                    {entry.result}
                  </td>
                  <td className="px-3 py-2">{entry.score}</td>
                  <td className="px-3 py-2 text-slate-300">
                    зон: {entry.recheckedZonesCount || 0}, -{entry.recheckAdjustment || "0.000"}
                  </td>
                  <td className="px-3 py-2 text-slate-300">{entry.duration}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
});
