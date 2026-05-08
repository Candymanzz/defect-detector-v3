import { memo } from "react";
import { formatDuration } from "../../shared/lib/lib";

export const BatchTestSection = memo(function BatchTestSection({ title, logs, summary }) {
  return (
    <section className="rounded-2xl border border-slate-700 bg-panel p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-medium">{title}</h2>
        {summary && (
          <div className="text-sm text-slate-300">
            Годных: <span className="text-green-400">{summary.good_count}</span> | Брак:{" "}
            <span className="text-red-400">{summary.defect_count}</span> | Брак, %:{" "}
            <span className="text-amber-300">{summary.defect_percent}</span>
          </div>
        )}
      </div>
      <div className="max-h-80 overflow-y-auto rounded-xl border border-slate-700">
        <table className="w-full border-collapse text-sm">
          <thead className="bg-slate-900 text-slate-300">
            <tr>
              <th className="px-3 py-2 text-left">Файл</th>
              <th className="px-3 py-2 text-left">Результат</th>
              <th className="px-3 py-2 text-left">Score</th>
              <th className="px-3 py-2 text-left">Длительность</th>
            </tr>
          </thead>
          <tbody>
            {logs.length === 0 ? (
              <tr>
                <td className="px-3 py-4 text-slate-500" colSpan={4}>
                  Тесты еще не запускались
                </td>
              </tr>
            ) : (
              logs.map((entry, index) => (
                <tr key={`${entry.filename}-${index}`} className="border-t border-slate-800">
                  <td className="px-3 py-2">{entry.filename}</td>
                  <td
                    className={`px-3 py-2 ${entry.status === "БРАК" ? "text-red-400" : "text-green-400"}`}
                  >
                    {entry.status}
                  </td>
                  <td className="px-3 py-2">{Number(entry.score).toFixed(3)}</td>
                  <td className="px-3 py-2 text-slate-300">
                    {formatDuration(Number(entry.duration_ms))}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
});
