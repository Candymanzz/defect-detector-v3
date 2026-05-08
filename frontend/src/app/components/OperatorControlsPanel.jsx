import { memo } from "react";
import { SlidersHorizontal } from "lucide-react";
import { OperatorPrimaryToolbarButtons } from "./OperatorPrimaryToolbarButtons";

const ROI_FIELDS = [
  { key: "x", label: "ROI X" },
  { key: "y", label: "ROI Y" },
  { key: "w", label: "ROI W" },
  { key: "h", label: "ROI H" }
];

export const OperatorControlsPanel = memo(function OperatorControlsPanel({
  productType,
  onProductTypeChange,
  onPickImage,
  onUploadReference,
  onUploadReferenceFromCamera,
  onRunInspection,
  onRunInspectionFromCamera,
  onRunBatchTest,
  onReset,
  capturedFile,
  busy,
  apiBaseUrlDraft,
  onApiBaseUrlDraftChange,
  onSaveApiBaseUrl,
  cameraSource,
  onCameraSourceChange,
  roi,
  onRoiChange,
  onSaveRoi,
  threshold,
  onThresholdChange
}) {
  return (
    <section className="rounded-2xl border border-slate-700 bg-panel p-4">
      <div className="grid gap-3 md:grid-cols-8">
        <input
          className="rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm outline-none ring-amber-300 focus:ring"
          value={productType}
          onChange={(event) => onProductTypeChange(event.target.value)}
          placeholder="Тип изделия"
        />
        <label className="rounded-lg border border-dashed border-slate-600 px-3 py-2 text-sm text-slate-300">
          <input type="file" accept="image/*" className="hidden" onChange={onPickImage} />
          Выбрать изображение
        </label>
        <OperatorPrimaryToolbarButtons
          onUploadReference={onUploadReference}
          onUploadReferenceFromCamera={onUploadReferenceFromCamera}
          onRunInspection={onRunInspection}
          onRunInspectionFromCamera={onRunInspectionFromCamera}
          onRunBatchTest={onRunBatchTest}
          onReset={onReset}
          capturedFile={capturedFile}
          busy={busy}
        />
      </div>

      <div className="mt-3 grid gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
        <input
          className="rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm outline-none ring-amber-300 focus:ring"
          value={apiBaseUrlDraft}
          onChange={(event) => onApiBaseUrlDraftChange(event.target.value)}
          placeholder="Backend API URL"
        />
        <button
          onClick={onSaveApiBaseUrl}
          disabled={busy || !apiBaseUrlDraft.trim()}
          className="rounded-lg bg-cyan-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
        >
          Save backend URL
        </button>
      </div>

      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <input
          className="rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm outline-none ring-amber-300 focus:ring"
          value={cameraSource}
          onChange={(event) => onCameraSourceChange(event.target.value)}
          placeholder="URL camera server capture endpoint"
        />
        <div className="rounded-lg border border-slate-700 bg-panelSoft px-3 py-2 text-sm text-slate-300">
          Пример: http://localhost:8080
        </div>
      </div>

      <div className="mt-3 grid gap-3 md:grid-cols-6">
        {ROI_FIELDS.map((field) => (
          <label
            key={field.key}
            className="flex items-center gap-2 rounded-lg border border-slate-600 bg-slate-900 px-3 py-2 text-sm"
          >
            <span className="text-slate-300">{field.label}</span>
            <input
              type="number"
              min={0}
              max={1}
              step={0.01}
              value={roi[field.key]}
              onChange={(event) =>
                onRoiChange((prev) => ({ ...prev, [field.key]: Number(event.target.value) }))
              }
              className="w-full bg-transparent text-right text-slate-100 outline-none"
            />
          </label>
        ))}
        <button
          onClick={onSaveRoi}
          disabled={busy}
          className="rounded-lg bg-emerald-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
        >
          Сохранить ROI
        </button>
        <button
          onClick={() => onRoiChange({ x: 0, y: 0, w: 1, h: 1 })}
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
          onChange={(event) => onThresholdChange(Number(event.target.value))}
          className="w-full"
        />
      </div>
    </section>
  );
});
