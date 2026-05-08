export function ResultImageCard({ title, imageSrc, alt }) {
  return (
    <article className="rounded-xl border border-slate-700 bg-panelSoft p-3">
      <h3 className="mb-2 text-sm font-medium text-slate-200">{title}</h3>
      <div className="aspect-video overflow-hidden rounded border border-slate-700 bg-black/40">
        {imageSrc ? (
          <img src={imageSrc} alt={alt} className="h-full w-full object-contain" />
        ) : (
          <div className="flex h-full items-center justify-center text-xs text-slate-500">
            Нет данных
          </div>
        )}
      </div>
    </article>
  );
}
