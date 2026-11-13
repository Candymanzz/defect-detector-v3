import { resolveInspectionResultState } from "../../shared/inspectResult";
import { compareFrameIds } from "../../shared/lib/frameIds";
import type { InspectBucketResultPayload, InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import type { InspectionStats, InspectionStatsGroup } from "./type";

type Counts = { total: number; passed: number; failed: number };
export type InspectionCounts = Counts & { groups: InspectionStatsGroup[] };
type Decision = "pass" | "fail" | undefined;
// Ten cameras use two bits per camera; no frames, images, heatmaps or full results are retained.
type Cycle = { pass: number; fail: number; authoritative: number; groupPass: number };
const emptyCounts = (): Counts => ({ total: 0, passed: 0, failed: 0 });

export class InspectionStatistics {
  private cameras: number[] = [];
  private indexes = new Map<number, number>();
  private groups: Array<{ cameraIds: number[]; mask: number; counts: Counts }> = [];
  private cycles = new Map<string, Cycle | null>();
  private totals = emptyCounts();
  private listeners = new Set<() => void>();
  private snapshot: InspectionCounts = { ...emptyCounts(), groups: [] };
  private generation = 0;
  private observedFrames = new Map<number, { id: string; timestamp: number; generation: number }>();

  constructor(cameraIds: number[]) { this.configure(cameraIds); }
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  getSnapshot = () => this.snapshot;

  configure(cameraIds: number[]) {
    const cameras = [...new Set(cameraIds)].sort((a, b) => a - b).slice(0, 10);
    if (JSON.stringify(cameras) === JSON.stringify(this.cameras)) return;
    this.cameras = cameras;
    for (const id of cameras) if (!this.indexes.has(id)) this.indexes.set(id, this.indexes.size);
    this.groups = [cameras.slice(0, 5), cameras.slice(5, 10)].filter(ids => ids.length).map(ids => ({
      cameraIds: ids, mask: ids.reduce((mask, id) => mask | this.bit(id), 0), counts: emptyCounts(),
    }));
    this.totals = emptyCounts();
    for (const cycle of this.cycles.values()) if (cycle) this.adjust(undefined, cycle);
    this.publish();
  }

  /** Manual reset keeps only old IDs, so a delayed artifact cannot count an old cycle again. */
  reset = () => {
    for (const id of this.cycles.keys()) this.cycles.set(id, null);
    this.clearCounts();
    this.publish();
  };

  /** Capture/preview counters restart once per generation, rather than once per camera. */
  observeFrame(frame: Pick<PreviewFramePayload, "camera_id" | "frame_id" | "server_ts_ms">) {
    const previous = this.observedFrames.get(frame.camera_id);
    if (previous && frame.server_ts_ms < previous.timestamp) return;
    if (previous && compareFrameIds(frame.frame_id, previous.id) < 0 && previous.generation === this.generation) {
      this.generation++;
      this.cycles.clear();
      this.clearCounts();
      this.publish();
    }
    this.observedFrames.set(frame.camera_id, { id: frame.frame_id, timestamp: frame.server_ts_ms, generation: this.generation });
  }

  recordInspection(result: InspectResultPayload) {
    if (result.test_analyze) return;
    const verdict = resolveInspectionResultState(result);
    if (verdict !== "pass" && verdict !== "fail") return;
    const id = result.inspection_id ?? result.frame_id;
    if (this.cycles.get(id) === null) return;
    const bit = this.bit(result.camera_id);
    if (!bit) return;
    this.update(id, cycle => ({ ...cycle,
      pass: verdict === "pass" ? cycle.pass | bit : cycle.pass & ~bit,
      fail: verdict === "fail" ? cycle.fail | bit : cycle.fail & ~bit,
    }));
  }

  recordBucket(result: InspectBucketResultPayload) {
    if (String(result.session_state) === "TEST" || result.session_state === "NO_REFERENCE") return;
    if (result.frames.length && result.frames.every(frame => frame.action === "CAPTURE" || frame.python_status === "NO_REFERENCE")) return;
    const group = this.groups.findIndex(group => result.bucket_camera_ids.some(id => group.cameraIds.includes(id)));
    if (group < 0) return;
    const groupBit = 1 << group;
    this.update(String(result.trigger_sequence), cycle => ({ ...cycle,
      authoritative: cycle.authoritative | groupBit,
      groupPass: result.overall_pass ? cycle.groupPass | groupBit : cycle.groupPass & ~groupBit,
    }));
  }

  private bit(cameraId: number) {
    const index = this.indexes.get(cameraId);
    return index === undefined || index >= 30 ? 0 : 1 << index;
  }
  private verdicts(cycle?: Cycle): Decision[] {
    return this.groups.map((group, index) => !cycle ? undefined
      : cycle.authoritative & (1 << index) ? (cycle.groupPass & (1 << index) ? "pass" : "fail")
      : cycle.fail & group.mask ? "fail" : cycle.pass & group.mask ? "pass" : undefined);
  }
  private adjust(previous: Cycle | undefined, next: Cycle) {
    const before = this.verdicts(previous), after = this.verdicts(next);
    const overall = (values: Decision[]): Decision => values.includes("fail") ? "fail" : values.includes("pass") ? "pass" : undefined;
    let changed = this.move(this.totals, overall(before), overall(after));
    this.groups.forEach((group, index) => { changed = this.move(group.counts, before[index], after[index]) || changed; });
    return changed;
  }
  private move(counts: Counts, before: Decision, after: Decision) {
    if (before === after) return false;
    if (before) { counts.total--; counts[before === "pass" ? "passed" : "failed"]--; }
    if (after) { counts.total++; counts[after === "pass" ? "passed" : "failed"]++; }
    return true;
  }
  private update(id: string, update: (cycle: Cycle) => Cycle) {
    const previous = this.cycles.get(id);
    if (previous === null) return;
    const next = update(previous ?? { pass: 0, fail: 0, authoritative: 0, groupPass: 0 });
    this.cycles.set(id, next);
    if (this.adjust(previous, next)) this.publish();
  }
  private clearCounts() {
    this.totals = emptyCounts();
    this.groups.forEach(group => { group.counts = emptyCounts(); });
  }
  private publish() {
    this.snapshot = { ...this.totals, groups: this.groups.map((group, index) => ({
      id: `group-${index + 1}`, label: `Группа ${index + 1}`, cameraIds: [...group.cameraIds], ...group.counts,
    })) } satisfies Pick<InspectionStats, "total" | "passed" | "failed" | "groups">;
    this.listeners.forEach(listener => listener());
  }
}
