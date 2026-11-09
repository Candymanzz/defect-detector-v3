import { copyExclusions, validateExclusions } from "../../shared/inspectionExclusions";
import type { ExclusionPoint, Exclusions } from "../../shared/inspectionExclusions";
import { errorMessage } from "../../shared/lib/errors";

type ExclusionApi = {
  read: () => Promise<Exclusions>;
  write: (zones: Exclusions) => Promise<Exclusions>;
};

type EditorState = {
  saved: Exclusions;
  draft: Exclusions;
  points: ExclusionPoint[] | null;
  selected: number | null;
  ready: boolean;
  busy: boolean;
  message: string;
  error: boolean;
  undo: Exclusions[];
};

/** One instance belongs to one camera; late replies can only update that instance. */
export class ExclusionEditorModel {
  private state: EditorState = {
    saved: [], draft: [], points: null, selected: null, ready: false, busy: false,
    message: "Загрузка зон…", error: false, undo: [],
  };
  private listeners = new Set<() => void>();
  private loadVersion = 0;
  private hasLoaded = false;

  constructor(private api: ExclusionApi) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  getSnapshot = () => this.state;
  get dirty() { return JSON.stringify(this.state.saved) !== JSON.stringify(this.state.draft); }

  private update(patch: Partial<EditorState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }

  async load(preserveDraft = false) {
    const version = ++this.loadVersion;
    this.update({ busy: true, ready: false, message: "Загрузка зон…", error: false });
    try {
      const zones = await this.api.read();
      const error = validateExclusions(zones);
      if (error) throw new Error(error);
      if (version !== this.loadVersion) return;
      const keepDraft = preserveDraft && this.hasLoaded;
      this.hasLoaded = true;
      this.update({
        saved: copyExclusions(zones), ready: true, busy: false,
        ...(keepDraft ? {} : { draft: copyExclusions(zones), points: null, selected: null, undo: [] }),
        message: keepDraft ? "Состояние сервера перечитано. Черновик сохранён." : "Зоны загружены.", error: false,
      });
    } catch (error) {
      if (version === this.loadVersion) this.update({ busy: false, error: true, message: errorMessage(error) });
    }
  }

  getDraft(): Exclusions {
    if (!this.state.ready || this.state.busy) throw new Error("Дождитесь загрузки или сохранения зон.");
    if (this.state.points !== null) throw new Error("Завершите рисование зоны или нажмите «Отмена рисования».");
    const error = validateExclusions(this.state.draft);
    if (error) throw new Error(error);
    return copyExclusions(this.state.draft);
  }

  start = () => {
    if (!this.state.ready || this.state.busy || this.state.draft.length >= 32) return;
    this.update({ points: [], selected: null, message: "Отмечайте вершины на эталоне; затем завершите зону.", error: false });
  };
  addPoint(point: ExclusionPoint) {
    if (this.state.busy || this.state.points === null) return;
    if (this.state.points.length >= 128) {
      this.update({ error: true, message: "В зоне допускается не более 128 точек." });
      return;
    }
    this.update({ points: [...this.state.points, point] });
  }
  removePoint = () => {
    if (!this.state.busy && this.state.points) this.update({ points: this.state.points.slice(0, -1) });
  };
  cancelDrawing = () => { if (!this.state.busy) this.update({ points: null, message: "Рисование отменено.", error: false }); };
  finish = () => {
    if (this.state.busy || this.state.points === null) return;
    const error = validateExclusions([this.state.points]);
    if (error) { this.update({ message: error, error: true }); return; }
    const next = [...this.state.draft, this.state.points];
    this.change(next);
    this.update({ points: null, selected: next.length - 1 });
  };
  select(index: number) { if (!this.state.busy && this.state.points === null) this.update({ selected: index }); }
  removeSelected = () => {
    if (this.state.selected !== null) this.change(this.state.draft.filter((_, i) => i !== this.state.selected));
  };
  clear = () => { this.change([]); };
  private change(draft: Exclusions) {
    if (!this.state.ready || this.state.busy) return;
    this.update({ draft: copyExclusions(draft), selected: null, undo: [...this.state.undo, this.state.draft].slice(-50),
      message: "Черновик изменён. Сохраните зоны или проверьте кадр.", error: false });
  }
  undo = () => {
    const previous = this.state.undo[this.state.undo.length - 1];
    if (this.state.busy || !previous) return;
    this.update({ draft: previous, undo: this.state.undo.slice(0, -1), selected: null, points: null,
      message: "Изменение отменено.", error: false });
  };
  cancel = () => {
    if (!this.state.busy) this.update({ draft: copyExclusions(this.state.saved), undo: [], selected: null, points: null,
      message: "Черновик отменён.", error: false });
  };

  async save() {
    const zones = this.getDraft();
    if (!this.dirty) return;
    this.update({ busy: true, error: false, message: "Сохранение зон…" });
    try {
      const saved = await this.api.write(zones);
      const error = validateExclusions(saved);
      if (error) throw new Error(error);
      this.update({ saved: copyExclusions(saved), draft: copyExclusions(saved), undo: [], busy: false,
        message: "Зоны сохранены. Для нового результата повторите проверку кадра.", error: false });
    } catch (error) {
      // A failed/aborted request may have reached the server. Reconcile before allowing another write.
      let suffix = " Состояние сервера перечитано; черновик сохранён.";
      let reconciled = false;
      try {
        const saved = await this.api.read();
        const invalid = validateExclusions(saved);
        if (!invalid) {
          this.update({ saved: copyExclusions(saved), ready: true });
          reconciled = true;
        }
      } catch { /* Keep the draft and block writes until a successful read. */ }
      if (!reconciled) {
        this.update({ ready: false });
        suffix = " Черновик сохранён. Перечитайте состояние сервера перед повтором.";
      }
      this.update({ busy: false, error: true, message: errorMessage(error) + suffix });
      throw error;
    }
  }
}
