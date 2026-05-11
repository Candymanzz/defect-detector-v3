import { memo, useCallback } from "react";
import { Camera, FlaskConical, ImagePlus, RotateCcw } from "lucide-react";

export const OperatorPrimaryToolbarButtons = memo(function OperatorPrimaryToolbarButtons({
  onUploadReference,
  onUploadReferenceFromCamera,
  onRunInspection,
  onRunInspectionFromCamera,
  onRunBatchTest,
  onReset,
  capturedFile,
  busy
}) {
  const runNormalBatch = useCallback(() => onRunBatchTest("normal"), [onRunBatchTest]);
  const runBrackBatch = useCallback(() => onRunBatchTest("brack"), [onRunBatchTest]);

  return (
    <>
      <button
        type="button"
        onClick={onUploadReference}
        disabled={busy || !capturedFile}
        className="flex items-center justify-center gap-2 rounded-lg bg-amber-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
      >
        <ImagePlus size={16} /> Задать эталон
      </button>
      <button
        type="button"
        onClick={onUploadReferenceFromCamera}
        disabled={busy}
        className="flex items-center justify-center gap-2 rounded-lg bg-amber-300 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
      >
        <Camera size={16} /> Эталон с камеры
      </button>
      <button
        type="button"
        onClick={onRunInspection}
        disabled={busy || !capturedFile}
        className="rounded-lg bg-sky-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
      >
        Проверить
      </button>
      <button
        type="button"
        onClick={onRunInspectionFromCamera}
        disabled={busy}
        className="flex items-center justify-center gap-2 rounded-lg bg-indigo-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
      >
        <Camera size={16} /> Проверить с камеры
      </button>
      <button
        type="button"
        onClick={runNormalBatch}
        disabled={busy}
        className="flex items-center justify-center gap-2 rounded-lg bg-fuchsia-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
      >
        <FlaskConical size={16} /> Тест нормальные
      </button>
      <button
        type="button"
        onClick={runBrackBatch}
        disabled={busy}
        className="flex items-center justify-center gap-2 rounded-lg bg-rose-500 px-3 py-2 text-sm font-medium text-slate-950 disabled:opacity-60"
      >
        <FlaskConical size={16} /> Тест брак
      </button>
      <button
        type="button"
        onClick={onReset}
        className="flex items-center justify-center gap-2 rounded-lg bg-slate-700 px-3 py-2 text-sm font-medium"
      >
        <RotateCcw size={16} /> Сброс
      </button>
    </>
  );
});
