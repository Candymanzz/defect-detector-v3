import { useMemo, useState } from "react";
import { AlertTriangle, CheckCircle2, FlaskConical, ImagePlus, RotateCcw, SlidersHorizontal } from "lucide-react";

const API_URL = "http://localhost:8000";

const toDataUrl = (base64) => (base64 ? `data:image/png;base64,${base64}` : "");
const formatDuration = (durationMs) =>
  durationMs < 1000 ? `${Math.round(durationMs)} ms` : `${(durationMs / 1000).toFixed(2)} s`;

function App() {
  const [productType, setProductType] = useState("bucket-default");
  const [threshold, setThreshold] = useState(0.25);
  const [referencePreview, setReferencePreview] = useState("");
  const [capturedFile, setCapturedFile] = useState(null);
  const [status, setStatus] = useState("ОЖИДАНИЕ");
  const [score, setScore] = useState(0);
  const [images, setImages] = useState({
    original: "",
    diff: "",
    heatmap: ""
  });
  const [logs, setLogs] = useState([]);
  const [busy, setBusy] = useState(false);
  const [normalTestLogs, setNormalTestLogs] = useState([]);
  const [normalTestSummary, setNormalTestSummary] = useState(null);
  const [brackTestLogs, setBrackTestLogs] = useState([]);
  const [brackTestSummary, setBrackTestSummary] = useState(null);

  const statusClass = useMemo(() => {
    if (status === "ГОДЕН") return "bg-ok/20 text-ok border-ok/40";
    if (status === "БРАК") return "bg-ng/20 text-ng border-ng/40";
    return "bg-slate-700/40 text-slate-300 border-slate-600";
  }, [status]);

  const handlePickImage = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setCapturedFile(file);
    setImages((prev) => ({ ...prev, original: URL.createObjectURL(file) }));
  };

  const uploadReference = async () => {
    if (!capturedFile) return;
    setBusy(true);
    try {
      const formData = new FormData();
      formData.append("product_type", productType);
      formData.append("file", capturedFile);

      const res = await fetch(`${API_URL}/upload-ref`, {
        method: "POST",
        body: formData
      });
      if (!res.ok) throw new Error("Не удалось загрузить эталон");
      setReferencePreview(URL.createObjectURL(capturedFile));
    } catch (error) {
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  };

  const runInspection = async () => {
    if (!capturedFile) return;
    const startedAt = performance.now();
    setBusy(true);
    try {
      const formData = new FormData();
      formData.append("product_type", productType);
      formData.append("threshold", String(threshold));
      formData.append("file", capturedFile);

      const res = await fetch(`${API_URL}/inspect`, {
        method: "POST",
        body: formData
      });
      if (!res.ok) throw new Error("Ошибка проверки");

      const data = await res.json();
      const elapsedMs = performance.now() - startedAt;
      setStatus(data.status);
      setScore(data.anomaly_score);
      setImages((prev) => ({
        ...prev,
        diff: toDataUrl(data.diff_map_b64),
        heatmap: toDataUrl(data.heatmap_b64)
      }));
      setLogs((prev) => [
        {
          ts: new Date().toLocaleTimeString(),
          result: data.status,
          score: Number(data.anomaly_score).toFixed(3),
          duration: formatDuration(elapsedMs)
        },
        ...prev
      ]);
    } catch (error) {
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  };

  const runBatchTest = async (dataset) => {
    setBusy(true);
    try {
      const formData = new FormData();
      formData.append("product_type", productType);
      formData.append("threshold", String(threshold));
      formData.append("dataset", dataset);

      const res = await fetch(`${API_URL}/test-run`, {
        method: "POST",
        body: formData
      });
      if (!res.ok) throw new Error("Ошибка тестирования");

      const data = await res.json();
      if (dataset === "normal") {
        setNormalTestLogs(data.logs || []);
        setNormalTestSummary(data.summary || null);
      } else {
        setBrackTestLogs(data.logs || []);
        setBrackTestSummary(data.summary || null);
      }
    } catch (error) {
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  };

  const resetAll = () => {
    setStatus("ОЖИДАНИЕ");
    setScore(0);
    setCapturedFile(null);
    setImages({ original: "", diff: "", heatmap: "" });
    setNormalTestLogs([]);
    setNormalTestSummary(null);
    setBrackTestLogs([]);
    setBrackTestSummary(null);
  };

  return (
    <main className="min-h-screen bg-transparent p-6 text-slate-100">
      <div className="mx-auto max-w-7xl space-y-6">
        <header className="rounded-2xl border border-amber-500/30 bg-panel px-6 py-4 shadow-2xl shadow-black/20">
          <h1 className="text-2xl font-semibold tracking-wide text-amber-300">QC Dashboard - Bucket Inspection</h1>
          <p className="text-sm text-slate-400">Выравнивание, карта разницы и оценка аномалий в реальном времени.</p>
        </header>

        <section className="grid gap-6 lg:grid-cols-3">
          <div className="rounded-2xl border border-slate-700 bg-panel p-4 lg:col-span-2">
            <h2 className="mb-3 text-lg font-medium text-slate-100">Кадр / Последний захват</h2>
            <div className="aspect-video overflow-hidden rounded-xl border border-slate-700 bg-black/40">
              {images.original ? (
                <img src={images.original} alt="original" className="h-full w-full object-contain" />
              ) : (
                <div className="flex h-full items-center justify-center text-slate-500">Загрузите изображение для проверки</div>
              )}
            </div>
          </div>

          <div className="space-y-4 rounded-2xl border border-slate-700 bg-panel p-4">
            <div className={`rounded-xl border px-4 py-3 ${statusClass}`}>
              <div className="flex items-center gap-2 text-lg font-semibold">
                {status === "БРАК" ? <AlertTriangle /> : <CheckCircle2 />}
                {status}
              </div>
            </div>

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

            <div className="rounded-xl border border-slate-700 bg-panelSoft p-3">
              <p className="mb-2 text-sm text-slate-300">Golden Template</p>
              <div className="h-36 overflow-hidden rounded border border-slate-700 bg-black/40">
                {referencePreview ? (
                  <img src={referencePreview} alt="reference" className="h-full w-full object-contain" />
                ) : (
                  <div className="flex h-full items-center justify-center text-xs text-slate-500">Эталон не задан</div>
                )}
              </div>
            </div>
          </div>
        </section>

        <section className="grid gap-4 rounded-2xl border border-slate-700 bg-panel p-4 md:grid-cols-3">
          {[
            { title: "Оригинал", src: images.original },
            { title: "Карта разницы", src: images.diff },
            { title: "Тепловая карта", src: images.heatmap }
          ].map((item) => (
            <article key={item.title} className="rounded-xl border border-slate-700 bg-panelSoft p-3">
              <h3 className="mb-2 text-sm font-medium text-slate-200">{item.title}</h3>
              <div className="aspect-video overflow-hidden rounded border border-slate-700 bg-black/40">
                {item.src ? (
                  <img src={item.src} alt={item.title} className="h-full w-full object-contain" />
                ) : (
                  <div className="flex h-full items-center justify-center text-xs text-slate-500">Нет данных</div>
                )}
              </div>
            </article>
          ))}
        </section>

        <section className="rounded-2xl border border-slate-700 bg-panel p-4">
          <div className="grid gap-3 md:grid-cols-7">
            <input
              className="rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm outline-none ring-amber-300 focus:ring"
              value={productType}
              onChange={(event) => setProductType(event.target.value)}
              placeholder="Тип изделия"
            />
            <label className="rounded-lg border border-dashed border-slate-600 px-3 py-2 text-sm text-slate-300">
              <input type="file" accept="image/*" className="hidden" onChange={handlePickImage} />
              Выбрать изображение
            </label>
            <button
              onClick={uploadReference}
              disabled={busy || !capturedFile}
              className="flex items-center justify-center gap-2 rounded-lg bg-amber-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              <ImagePlus size={16} /> Задать эталон
            </button>
            <button
              onClick={runInspection}
              disabled={busy || !capturedFile}
              className="rounded-lg bg-sky-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              Проверить
            </button>
            <button
              onClick={() => runBatchTest("normal")}
              disabled={busy}
              className="flex items-center justify-center gap-2 rounded-lg bg-fuchsia-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              <FlaskConical size={16} /> Тест нормальные
            </button>
            <button
              onClick={() => runBatchTest("brack")}
              disabled={busy}
              className="flex items-center justify-center gap-2 rounded-lg bg-rose-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              <FlaskConical size={16} /> Тест брак
            </button>
            <button
              onClick={resetAll}
              className="flex items-center justify-center gap-2 rounded-lg bg-slate-700 px-3 py-2 text-sm font-medium"
            >
              <RotateCcw size={16} /> Сброс
            </button>
          </div>

          <div className="mt-4 flex items-center gap-3 rounded-lg border border-slate-700 bg-panelSoft p-3">
            <SlidersHorizontal size={16} className="text-amber-300" />
            <span className="text-sm text-slate-300">Threshold: {threshold.toFixed(2)}</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.01}
              value={threshold}
              onChange={(event) => setThreshold(Number(event.target.value))}
              className="w-full"
            />
          </div>
        </section>

        <section className="rounded-2xl border border-slate-700 bg-panel p-4">
          <h2 className="mb-3 text-lg font-medium">Лог проверок</h2>
          <div className="max-h-60 overflow-y-auto rounded-xl border border-slate-700">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-slate-900 text-slate-300">
                <tr>
                  <th className="px-3 py-2 text-left">Время</th>
                  <th className="px-3 py-2 text-left">Результат</th>
                  <th className="px-3 py-2 text-left">Score</th>
                  <th className="px-3 py-2 text-left">Длительность</th>
                </tr>
              </thead>
              <tbody>
                {logs.length === 0 ? (
                  <tr>
                    <td className="px-3 py-4 text-slate-500" colSpan={4}>
                      Пока нет проверок
                    </td>
                  </tr>
                ) : (
                  logs.map((entry, index) => (
                    <tr key={`${entry.ts}-${index}`} className="border-t border-slate-800">
                      <td className="px-3 py-2">{entry.ts}</td>
                      <td className={`px-3 py-2 ${entry.result === "БРАК" ? "text-red-400" : "text-green-400"}`}>
                        {entry.result}
                      </td>
                      <td className="px-3 py-2">{entry.score}</td>
                      <td className="px-3 py-2 text-slate-300">{entry.duration}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>

        <section className="rounded-2xl border border-slate-700 bg-panel p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-lg font-medium">Тесты по папке normal</h2>
            {normalTestSummary && (
              <div className="text-sm text-slate-300">
                Годных: <span className="text-green-400">{normalTestSummary.good_count}</span> | Брак:{" "}
                <span className="text-red-400">{normalTestSummary.defect_count}</span> | Брак, %:{" "}
                <span className="text-amber-300">{normalTestSummary.defect_percent}</span>
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
                {normalTestLogs.length === 0 ? (
                  <tr>
                    <td className="px-3 py-4 text-slate-500" colSpan={4}>
                      Тесты еще не запускались
                    </td>
                  </tr>
                ) : (
                  normalTestLogs.map((entry, index) => (
                    <tr key={`${entry.filename}-${index}`} className="border-t border-slate-800">
                      <td className="px-3 py-2">{entry.filename}</td>
                      <td className={`px-3 py-2 ${entry.status === "БРАК" ? "text-red-400" : "text-green-400"}`}>
                        {entry.status}
                      </td>
                      <td className="px-3 py-2">{Number(entry.score).toFixed(3)}</td>
                      <td className="px-3 py-2 text-slate-300">{formatDuration(Number(entry.duration_ms))}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>

        <section className="rounded-2xl border border-slate-700 bg-panel p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-lg font-medium">Тесты по папке brack</h2>
            {brackTestSummary && (
              <div className="text-sm text-slate-300">
                Годных: <span className="text-green-400">{brackTestSummary.good_count}</span> | Брак:{" "}
                <span className="text-red-400">{brackTestSummary.defect_count}</span> | Брак, %:{" "}
                <span className="text-amber-300">{brackTestSummary.defect_percent}</span>
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
                {brackTestLogs.length === 0 ? (
                  <tr>
                    <td className="px-3 py-4 text-slate-500" colSpan={4}>
                      Тесты еще не запускались
                    </td>
                  </tr>
                ) : (
                  brackTestLogs.map((entry, index) => (
                    <tr key={`${entry.filename}-${index}`} className="border-t border-slate-800">
                      <td className="px-3 py-2">{entry.filename}</td>
                      <td className={`px-3 py-2 ${entry.status === "БРАК" ? "text-red-400" : "text-green-400"}`}>
                        {entry.status}
                      </td>
                      <td className="px-3 py-2">{Number(entry.score).toFixed(3)}</td>
                      <td className="px-3 py-2 text-slate-300">{formatDuration(Number(entry.duration_ms))}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </main>
  );
}

export default App;
