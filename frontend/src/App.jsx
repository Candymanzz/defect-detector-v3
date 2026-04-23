import { useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, Camera, CheckCircle2, FlaskConical, ImagePlus, RotateCcw, SlidersHorizontal } from "lucide-react";

const API_URL = "http://localhost:8000";

const toDataUrl = (base64) => (base64 ? `data:image/png;base64,${base64}` : "");
const formatDuration = (durationMs) =>
  durationMs < 1000 ? `${Math.round(durationMs)} ms` : `${(durationMs / 1000).toFixed(2)} s`;
const getErrorMessage = async (response, fallbackMessage) => {
  try {
    const payload = await response.json();
    if (payload?.detail) return String(payload.detail);
  } catch {
    // Ignore malformed error body and return fallback below.
  }
  return fallbackMessage;
};

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
  const [cameraSource, setCameraSource] = useState("http://localhost:8080");
  const [roi, setRoi] = useState({ x: 0.1, y: 0.1, w: 0.8, h: 0.8 });
  const [roiPolygon, setRoiPolygon] = useState([]);
  const [drawPolygonMode, setDrawPolygonMode] = useState(false);
  const [referenceImageSize, setReferenceImageSize] = useState({ width: 0, height: 0 });
  const [referenceBox, setReferenceBox] = useState({ left: 0, top: 0, width: 0, height: 0 });
  const referenceContainerRef = useRef(null);

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

  const uploadReferenceFromCamera = async () => {
    setBusy(true);
    try {
      const formData = new FormData();
      formData.append("product_type", productType);
      formData.append("camera_server_url", cameraSource);

      const res = await fetch(`${API_URL}/upload-ref-from-camera`, {
        method: "POST",
        body: formData
      });
      if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось задать эталон с камеры"));

      const data = await res.json();
      setReferencePreview(toDataUrl(data.reference_b64));
      setImages((prev) => ({ ...prev, original: toDataUrl(data.reference_b64) }));
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

  const runInspectionFromCamera = async () => {
    const startedAt = performance.now();
    setBusy(true);
    try {
      const formData = new FormData();
      formData.append("product_type", productType);
      formData.append("threshold", String(threshold));
      formData.append("camera_server_url", cameraSource);

      const res = await fetch(`${API_URL}/inspect-from-camera`, {
        method: "POST",
        body: formData
      });
      if (!res.ok) throw new Error(await getErrorMessage(res, "Ошибка проверки с камеры"));

      const data = await res.json();
      const elapsedMs = performance.now() - startedAt;
      setStatus(data.status);
      setScore(data.anomaly_score);
      setImages({
        original: toDataUrl(data.original_image_b64),
        diff: toDataUrl(data.diff_map_b64),
        heatmap: toDataUrl(data.heatmap_b64)
      });
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

  const saveRoi = async () => {
    setBusy(true);
    try {
      const formData = new FormData();
      formData.append("product_type", productType);
      formData.append("x", String(roi.x));
      formData.append("y", String(roi.y));
      formData.append("w", String(roi.w));
      formData.append("h", String(roi.h));

      const res = await fetch(`${API_URL}/roi`, {
        method: "POST",
        body: formData
      });
      if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось сохранить ROI"));
    } catch (error) {
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  };

  const recomputeReferenceBox = () => {
    const container = referenceContainerRef.current;
    if (!container || !referenceImageSize.width || !referenceImageSize.height) {
      setReferenceBox({ left: 0, top: 0, width: 0, height: 0 });
      return;
    }
    const containerWidth = container.clientWidth;
    const containerHeight = container.clientHeight;
    if (!containerWidth || !containerHeight) {
      setReferenceBox({ left: 0, top: 0, width: 0, height: 0 });
      return;
    }

    const containerAspect = containerWidth / containerHeight;
    const imageAspect = referenceImageSize.width / referenceImageSize.height;

    let drawWidth = containerWidth;
    let drawHeight = containerHeight;
    let offsetLeft = 0;
    let offsetTop = 0;

    if (imageAspect > containerAspect) {
      drawHeight = containerWidth / imageAspect;
      offsetTop = (containerHeight - drawHeight) / 2;
    } else {
      drawWidth = containerHeight * imageAspect;
      offsetLeft = (containerWidth - drawWidth) / 2;
    }

    setReferenceBox({
      left: offsetLeft,
      top: offsetTop,
      width: drawWidth,
      height: drawHeight
    });
  };

  useEffect(() => {
    recomputeReferenceBox();
    const onResize = () => recomputeReferenceBox();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [referenceImageSize.width, referenceImageSize.height, referencePreview]);

  const addPolygonPoint = (event) => {
    if (!drawPolygonMode || !referencePreview) return;
    const rect = event.currentTarget.getBoundingClientRect();
    if (!rect.width || !rect.height) return;

    const activeBox =
      referenceBox.width > 0 && referenceBox.height > 0
        ? referenceBox
        : { left: 0, top: 0, width: rect.width, height: rect.height };

    const localX = event.clientX - rect.left;
    const localY = event.clientY - rect.top;
    const inImageX = localX - activeBox.left;
    const inImageY = localY - activeBox.top;

    if (inImageX < 0 || inImageY < 0 || inImageX > activeBox.width || inImageY > activeBox.height) {
      return;
    }

    const x = Math.max(0, Math.min(1, inImageX / activeBox.width));
    const y = Math.max(0, Math.min(1, inImageY / activeBox.height));
    setRoiPolygon((prev) => [...prev, { x, y }]);
  };

  const savePolygonRoi = async () => {
    setBusy(true);
    try {
      const res = await fetch(`${API_URL}/roi-polygon`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          product_type: productType,
          points: roiPolygon
        })
      });
      if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось сохранить контур ROI"));
      setDrawPolygonMode(false);
    } catch (error) {
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  };

  const loadReferenceAndPolygon = async () => {
    try {
      const [referenceRes, polygonRes] = await Promise.all([
        fetch(`${API_URL}/reference/${encodeURIComponent(productType)}`),
        fetch(`${API_URL}/roi-polygon/${encodeURIComponent(productType)}`)
      ]);

      if (referenceRes.ok) {
        const referenceData = await referenceRes.json();
        setReferencePreview(toDataUrl(referenceData.reference_b64));
      } else {
        setReferencePreview("");
        setRoiPolygon([]);
        return;
      }

      if (polygonRes.ok) {
        const polygonData = await polygonRes.json();
        const points = Array.isArray(polygonData.points) ? polygonData.points : [];
        setRoiPolygon(points.map((p) => ({ x: Number(p.x), y: Number(p.y) })));
      } else {
        setRoiPolygon([]);
      }
    } catch {
      setReferencePreview("");
      setRoiPolygon([]);
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

  useEffect(() => {
    loadReferenceAndPolygon();
  }, [productType]);

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
              <div
                ref={referenceContainerRef}
                className={`relative h-36 overflow-hidden rounded border border-slate-700 bg-black/40 ${
                  drawPolygonMode ? "cursor-crosshair" : ""
                }`}
                onClick={addPolygonPoint}
              >
                {referencePreview ? (
                  <>
                    <img
                      src={referencePreview}
                      alt="reference"
                      className="h-full w-full object-contain"
                      onLoad={(event) => {
                        setReferenceImageSize({
                          width: event.currentTarget.naturalWidth,
                          height: event.currentTarget.naturalHeight
                        });
                        requestAnimationFrame(recomputeReferenceBox);
                      }}
                    />
                    {roiPolygon.length > 0 && referenceBox.width > 0 && referenceBox.height > 0 && (
                      <svg
                        className="pointer-events-none absolute"
                        style={{
                          left: `${referenceBox.left}px`,
                          top: `${referenceBox.top}px`,
                          width: `${referenceBox.width}px`,
                          height: `${referenceBox.height}px`
                        }}
                        viewBox="0 0 100 100"
                        preserveAspectRatio="none"
                      >
                        <polygon
                          points={roiPolygon.map((p) => `${(p.x * 100).toFixed(2)},${(p.y * 100).toFixed(2)}`).join(" ")}
                          fill="rgba(16, 185, 129, 0.2)"
                          stroke="rgb(16, 185, 129)"
                          strokeWidth="2"
                        />
                        {roiPolygon.map((p, index) => (
                          <circle
                            key={`${p.x}-${p.y}-${index}`}
                            cx={(p.x * 100).toFixed(2)}
                            cy={(p.y * 100).toFixed(2)}
                            r="2.2"
                            fill="rgb(251, 191, 36)"
                            stroke="rgb(255, 255, 255)"
                            strokeWidth="0.7"
                          />
                        ))}
                      </svg>
                    )}
                  </>
                ) : (
                  <div className="flex h-full items-center justify-center text-xs text-slate-500">Эталон не задан</div>
                )}
              </div>
              <div className="mt-2 flex flex-wrap gap-2">
                <button
                  onClick={() => setDrawPolygonMode((prev) => !prev)}
                  disabled={busy || !referencePreview}
                  className="rounded-lg bg-emerald-600 px-2 py-1 text-xs font-medium text-white disabled:opacity-60"
                >
                  {drawPolygonMode ? "Завершить рисование" : "Рисовать контур"}
                </button>
                <button
                  onClick={() => setRoiPolygon([])}
                  disabled={busy || roiPolygon.length === 0}
                  className="rounded-lg bg-slate-700 px-2 py-1 text-xs font-medium disabled:opacity-60"
                >
                  Очистить контур
                </button>
                <button
                  onClick={savePolygonRoi}
                  disabled={busy || roiPolygon.length < 3}
                  className="rounded-lg bg-amber-500 px-2 py-1 text-xs font-medium text-slate-950 disabled:opacity-60"
                >
                  Сохранить контур ROI
                </button>
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
          <div className="grid gap-3 md:grid-cols-8">
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
              onClick={uploadReferenceFromCamera}
              disabled={busy}
              className="flex items-center justify-center gap-2 rounded-lg bg-amber-300 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              <Camera size={16} /> Эталон с камеры
            </button>
            <button
              onClick={runInspection}
              disabled={busy || !capturedFile}
              className="rounded-lg bg-sky-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              Проверить
            </button>
            <button
              onClick={runInspectionFromCamera}
              disabled={busy}
              className="flex items-center justify-center gap-2 rounded-lg bg-indigo-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              <Camera size={16} /> Проверить с камеры
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

          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <input
              className="rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm outline-none ring-amber-300 focus:ring"
              value={cameraSource}
              onChange={(event) => setCameraSource(event.target.value)}
              placeholder="URL camera server capture endpoint"
            />
            <div className="rounded-lg border border-slate-700 bg-panelSoft px-3 py-2 text-sm text-slate-300">
              Пример: http://localhost:8080
            </div>
          </div>

          <div className="mt-3 grid gap-3 md:grid-cols-6">
            {[
              { key: "x", label: "ROI X" },
              { key: "y", label: "ROI Y" },
              { key: "w", label: "ROI W" },
              { key: "h", label: "ROI H" }
            ].map((field) => (
              <label key={field.key} className="flex items-center gap-2 rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm">
                <span className="text-slate-300">{field.label}</span>
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.01}
                  value={roi[field.key]}
                  onChange={(event) =>
                    setRoi((prev) => ({ ...prev, [field.key]: Number(event.target.value) }))
                  }
                  className="w-full bg-transparent text-right text-slate-100 outline-none"
                />
              </label>
            ))}
            <button
              onClick={saveRoi}
              disabled={busy}
              className="rounded-lg bg-emerald-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
            >
              Сохранить ROI
            </button>
            <button
              onClick={() => setRoi({ x: 0, y: 0, w: 1, h: 1 })}
              disabled={busy}
              className="rounded-lg bg-slate-700 px-3 py-2 text-sm font-medium"
            >
              ROI = весь кадр
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
