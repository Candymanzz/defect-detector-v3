import { describe, expect, it, vi } from "vitest";
import { ExclusionEditorModel } from "./ExclusionEditorModel";
import type { Exclusions } from "../../shared/inspectionExclusions";

const zone: Exclusions = [[[0, 0], [1, 0], [0, 1]]];
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

describe("exclusion editor", () => {
  it("starts with no contour and requires confirmation before a new zone enters the draft", async () => {
    const write = vi.fn(async (zones: Exclusions) => zones);
    const model = new ExclusionEditorModel({ read: async () => [], write });
    await model.load();
    expect(model.getSnapshot().points).toBeNull(); expect(model.getDraft()).toEqual([]);
    model.start(); zone[0].forEach(p => model.addPoint(p));
    expect(model.getSnapshot().draft).toEqual([]);
    expect(() => model.getDraft()).toThrow(/Подтвердите зону/);
    model.finish(); expect(model.getDraft()).toEqual(zone);
    expect(model.getSnapshot().message).toContain("Зона подтверждена");
    expect(write).not.toHaveBeenCalled();
  });
  it("removes the last point or all new points while preserving existing zones; clears all zones when idle", async () => {
    const model = new ExclusionEditorModel({ read: async () => zone, write: async z => z });
    await model.load(); model.start(); zone[0].forEach(p => model.addPoint(p));
    model.removePoint(); expect(model.getSnapshot().points).toEqual(zone[0].slice(0, -1));
    model.clear(); expect(model.getSnapshot().points).toEqual([]); expect(model.getSnapshot().draft).toEqual(zone);
    expect(() => model.getDraft()).toThrow(/Подтвердите/);
    model.cancelDrawing(); expect(model.getDraft()).toEqual(zone);
    model.clear(); expect(model.getDraft()).toEqual([]);
    model.undo(); expect(model.getDraft()).toEqual(zone);
  });
  it("records a whole vertex drag as one undo and blocks save while the pointer is down", async () => {
    const model = new ExclusionEditorModel({ read: async () => zone, write: async z => z });
    await model.load(); model.select(0);
    expect(model.beginVertexDrag(1)).toBe(true);
    model.dragVertex([.9, .1]); model.dragVertex([.8, .2]);
    expect(() => model.getDraft()).toThrow(/перемещение/);
    expect(model.getSnapshot().undo).toHaveLength(0);
    model.endVertexDrag();
    expect(model.getDraft()[0][1]).toEqual([.8, .2]);
    expect(model.getSnapshot().undo).toHaveLength(1);
    model.undo(); expect(model.getDraft()).toEqual(zone);
  });
  it("rejects a collapsed/self-intersecting contour on drag release and restores it on cancel", async () => {
    const model = new ExclusionEditorModel({ read: async () => zone, write: async z => z });
    await model.load(); model.select(0); model.beginVertexDrag(1); model.dragVertex([0, 0]); model.endVertexDrag();
    expect(model.getDraft()).toEqual(zone); expect(model.getSnapshot().error).toBe(true);
    model.beginVertexDrag(1); model.dragVertex([.8, .2]); model.endVertexDrag(true);
    expect(model.getDraft()).toEqual(zone); expect(model.getSnapshot().undo).toHaveLength(0);
  });
  it("moves vertices of an unfinished closed preview without committing or losing the draft", async () => {
    const model = new ExclusionEditorModel({ read: async () => [], write: async z => z });
    await model.load(); model.start(); zone[0].forEach(p => model.addPoint(p));
    model.beginVertexDrag(1); model.dragVertex([.8, .2]); model.endVertexDrag();
    expect(model.getSnapshot().points?.[1]).toEqual([.8, .2]);
    expect(() => model.getDraft()).toThrow(/Подтвердите зону/);
    model.finish(); expect(model.getDraft()[0][1]).toEqual([.8, .2]);
  });
  it("hydrates existing zones when the initial GET is retried, instead of keeping an empty uninitialized draft", async () => {
    const read = vi.fn<() => Promise<Exclusions>>().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(zone);
    const model = new ExclusionEditorModel({ read, write: async z => z });
    await model.load();
    expect(model.getSnapshot().ready).toBe(false);
    await model.load(true);
    expect(model.getDraft()).toEqual(zone);
    expect(model.dirty).toBe(false);
  });
  it("adds several zones, removes one/all, undoes and cancels independently of saved state", async () => {
    const write = vi.fn(async (zones: Exclusions) => zones);
    const model = new ExclusionEditorModel({ read: async () => zone, write });
    await model.load();
    model.start();
    zone[0].forEach((p) => model.addPoint(p));
    model.removePoint();
    model.addPoint([0, 1]);
    model.finish();
    expect(model.getDraft()).toEqual([...zone, ...zone]);
    model.select(0);
    model.removeSelected();
    expect(model.getDraft()).toEqual(zone);
    model.clear();
    expect(model.getDraft()).toEqual([]);
    model.undo();
    expect(model.getDraft()).toEqual(zone);
    model.cancel();
    expect(model.dirty).toBe(false);
    expect(write).not.toHaveBeenCalled();
  });

  it("blocks check/save during load, drawing and another save; accepts the server response", async () => {
    const pending = deferred<Exclusions>();
    const write = vi.fn(() => pending.promise);
    const model = new ExclusionEditorModel({ read: async () => zone, write });
    expect(() => model.getDraft()).toThrow();
    await model.load();
    model.start();
    expect(() => model.getDraft()).toThrow(/Подтвердите/);
    model.cancelDrawing();
    model.clear();
    const saving = model.save();
    await expect(model.save()).rejects.toThrow(/Дождитесь/);
    model.cancel();
    expect(model.getSnapshot().draft).toEqual([]);
    pending.resolve(zone);
    await saving;
    expect(write).toHaveBeenCalledOnce();
    expect(write).toHaveBeenCalledWith([]);
    expect(model.getDraft()).toEqual(zone);
    expect(model.dirty).toBe(false);
  });

  it("retains the draft after 400/timeout, rereads saved state, then retries", async () => {
    const read = vi.fn(async () => zone);
    const write = vi.fn<(zones: Exclusions) => Promise<Exclusions>>()
      .mockRejectedValueOnce({ responseBody: '{"detail":null,"error":"Backend unavailable"}' })
      .mockResolvedValueOnce([]);
    const model = new ExclusionEditorModel({ read, write });
    await model.load();
    model.clear();
    await expect(model.save()).rejects.toBeDefined();
    expect(model.getSnapshot().message).toContain("Backend unavailable");
    expect(model.getSnapshot().saved).toEqual(zone);
    expect(model.getDraft()).toEqual([]);
    expect(read).toHaveBeenCalledTimes(2);
    await model.save();
    expect(model.dirty).toBe(false);
  });

  it("blocks writes until failed reconciliation can be retried without losing the draft", async () => {
    const read = vi.fn<() => Promise<Exclusions>>().mockResolvedValueOnce(zone)
      .mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(zone);
    const write = vi.fn(async () => { throw new Error("timeout"); });
    const model = new ExclusionEditorModel({ read, write });
    await model.load(); model.clear();
    await expect(model.save()).rejects.toThrow("timeout");
    expect(() => model.getDraft()).toThrow();
    expect(model.getSnapshot().draft).toEqual([]);
    await model.load(true);
    expect(model.getDraft()).toEqual([]);
    expect(model.getSnapshot().saved).toEqual(zone);
  });

  it("isolates a camera's pending reply and ignores older loads", async () => {
    const oldLoad = deferred<Exclusions>();
    const read = vi.fn().mockReturnValueOnce(oldLoad.promise).mockResolvedValueOnce([]);
    const oldCamera = new ExclusionEditorModel({ read, write: async (z) => z });
    const loading = oldCamera.load();
    await oldCamera.load();
    const newCamera = new ExclusionEditorModel({ read: async () => zone, write: async (z) => z });
    await newCamera.load();
    oldLoad.resolve(zone);
    await loading;
    expect(oldCamera.getDraft()).toEqual([]);
    expect(newCamera.getDraft()).toEqual(zone);
  });
});
