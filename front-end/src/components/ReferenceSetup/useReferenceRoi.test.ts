import { beforeEach, describe, expect, it, vi } from "vitest";
import type { InterestPointNorm } from "../../shared/ws";

const state = vi.hoisted(() => ({ slots: [] as unknown[], cursor: 0, stored: {} as Record<number, { jointRoiPoints?: InterestPointNorm[] }> }));
vi.mock("react", () => ({ useState: (initial: unknown) => {
  const index = state.cursor++;
  if (!(index in state.slots)) state.slots[index] = initial;
  return [state.slots[index], (next: unknown) => {
    state.slots[index] = typeof next === "function" ? next(state.slots[index]) : next;
  }];
} }));
vi.mock("../../shared/referenceImages", () => ({ getReferenceImage: (cameraId: number) => state.stored[cameraId] }));
import { useReferenceRoi } from "./useReferenceRoi";

const seam = [{x:.2,y:.4},{x:.8,y:.4},{x:.8,y:.6},{x:.2,y:.6}];
function render(useStored = true) {
  state.cursor = 0;
  return useReferenceRoi([5,6,7,8,9], [[5,6,7,8,9]], 0, null, useStored);
}
beforeEach(() => { state.slots = []; state.cursor = 0; state.stored = {}; });
describe("seam ROI editor lifecycle", () => {
  it("uses the acknowledged/archive seam camera after resetting old edits", () => {
    render().selectJointRoi(5);
    render().setJointRoiPolygon(seam);
    expect(render().jointCameraId).toBe(5);
    state.stored = {9:{jointRoiPoints:seam}};
    render().resetEditedRoisForCameraIds([5,6,7,8,9]);
    expect(render().jointCameraId).toBe(9);
    expect(render().getJointRoiPolygonForCameraIds([5,6,7,8,9])).toEqual(seam);
  });
  it("clears edits on partial seam-camera replacement while preserving siblings", () => {
    render().selectJointRoi(9);
    render().setJointRoiPolygon(seam);
    render().resetEditedRoisForCameraIds([6]);
    expect(render().jointRoiPolygon).toEqual(seam);
    render().resetEditedRoisForCameraIds([9]);
    expect(render().jointRoiPolygon).toEqual([]);
  });
  it("does not borrow a stored seam camera for a fresh reference", () => {
    state.stored = {9:{jointRoiPoints:seam}};
    expect(render(false).jointCameraId).toBe(5);
    expect(render(false).jointRoiPolygon).toEqual([]);
  });
});
