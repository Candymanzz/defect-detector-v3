import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { StoredLearnedCase } from "../../shared/referenceImages";

type Props = {
  cases: StoredLearnedCase[];
  loading: boolean;
  error: string | null;
  deletingId: string | null;
  onRemove: (id: string) => void;
  onOpen: (id: string) => void;
};

export function LearnedFramesDropdown({ cases, loading, error, deletingId, onRemove, onOpen }: Props) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  const panelId = useId();

  useLayoutEffect(() => {
    if (!open) return;
    const position = () => {
      const trigger = triggerRef.current;
      const panel = panelRef.current;
      if (!trigger || !panel) return;
      const rect = trigger.getBoundingClientRect();
      const width = Math.min(360, window.innerWidth - 24);
      const below = window.innerHeight - rect.bottom - 20;
      const above = rect.top - 20;
      const upwards = below < 180 && above > below;
      const maxHeight = Math.max(80, Math.min(420, upwards ? above : below));
      panel.style.width = `${width}px`;
      panel.style.maxHeight = `${maxHeight}px`;
      panel.style.left = `${Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12))}px`;
      panel.style.top = `${upwards ? Math.max(12, rect.top - Math.min(panel.scrollHeight, maxHeight) - 8) : Math.max(12, rect.bottom + 8)}px`;
    };
    position();
    const observer = new ResizeObserver(position);
    if (panelRef.current) observer.observe(panelRef.current);
    window.addEventListener("resize", position);
    document.addEventListener("scroll", position, true);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", position);
      document.removeEventListener("scroll", position, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !triggerRef.current?.contains(event.target) && !panelRef.current?.contains(event.target)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      triggerRef.current?.focus({ preventScroll: true });
    };
    document.addEventListener("pointerdown", outside);
    window.addEventListener("keydown", escape, true);
    return () => {
      document.removeEventListener("pointerdown", outside);
      window.removeEventListener("keydown", escape, true);
    };
  }, [open]);

  return <section className="reference-setup__object-section">
    <button ref={triggerRef} type="button" className="reference-setup__learned-trigger"
      aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(current => !current)}>
      <span>Доп. кадры анализа</span>
      <span className="reference-setup__learned-count">{loading ? "…" : cases.length}</span>
      <span aria-hidden="true" className="reference-setup__learned-chevron" />
    </button>
    {open && createPortal(
      <section id={panelId} ref={panelRef} className="reference-setup__learned-dropdown" role="region" aria-label="Дополнительные кадры анализа"
        onMouseDown={event => event.stopPropagation()}>
        <header><strong>Дополнительные кадры</strong><button type="button" aria-label="Закрыть дополнительные кадры" onClick={() => { setOpen(false); triggerRef.current?.focus({ preventScroll: true }); }}>×</button></header>
        {loading && <div className="reference-setup__learned-empty">Загрузка…</div>}
        {error && <div className="reference-setup__learned-empty" role="alert">{error}</div>}
        {!loading && !error && cases.length === 0 && <div className="reference-setup__learned-empty">Для камеры пока нет добавленных кадров</div>}
        <div className="reference-setup__learned-grid">
          {cases.map((item, index) => <figure key={item.id} className="reference-setup__learned-card">
            <button className="reference-setup__learned-delete" type="button" aria-label={`Удалить дополнительный фрагмент ${index + 1}`}
              title="Удалить из анализа" disabled={deletingId !== null} onClick={() => onRemove(item.id)}>{deletingId === item.id ? "…" : "×"}</button>
            <button className="reference-setup__learned-open" type="button" aria-label={`Увеличить дополнительный фрагмент ${index + 1}`}
              onClick={() => { setOpen(false); onOpen(item.id); }}>
              <img src={item.imageUrl} alt={`Дополнительный фрагмент ${index + 1}`} />
            </button>
            <figcaption>{item.note || `Фрагмент ${index + 1}`}</figcaption>
          </figure>)}
        </div>
      </section>, document.body,
    )}
  </section>;
}
