export function OriginalImagePanel({ imageSrc }) {
  return (
    <div className="rounded-2xl border border-slate-700 bg-panel p-4 lg:col-span-2">
      <h2 className="mb-3 text-lg font-medium text-slate-100">Кадр / Последний захват</h2>
      <div className="aspect-video overflow-hidden rounded-xl border border-slate-700 bg-black/40">
        {imageSrc ? (
          <img src={imageSrc} alt="original" className="h-full w-full object-contain" />
        ) : (
          <div className="flex h-full items-center justify-center text-slate-500">
            Загрузите изображение для проверки
          </div>
        )}
      </div>
    </div>
  );
}
