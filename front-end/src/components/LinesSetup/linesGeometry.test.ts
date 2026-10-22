import { describe, expect, it } from "vitest";
import {
  nearestOnLine,
  nearestSegment,
  normToPx,
  orderedRoiPick,
  pxToNorm,
  skipAfterInsert,
  splineSegment,
  type Pt,
} from "./linesGeometry";

describe("lines geometry", () => {
  it("converts normalised points like the server does", () => {
    expect(normToPx([0.5, 1], 101, 51)).toEqual([50, 50]);
    expect(pxToNorm([50, 50], 101, 51)).toEqual([0.5, 1]);
    expect(pxToNorm([-5, 999], 101, 51)).toEqual([0, 1]);
  });

  it("draws a straight line for two points and passes through all control points", () => {
    const line: Pt[] = [[0, 0], [100, 0]];
    const straight = splineSegment(line, 0);
    expect(straight[0]).toEqual([0, 0]);
    expect(straight[straight.length - 1]).toEqual([100, 0]);
    expect(straight.every((p) => Math.abs(p[1]) < 1e-9)).toBe(true);

    const bent: Pt[] = [[0, 0], [50, 20], [100, 0]];
    expect(splineSegment(bent, 0).slice(-1)[0]).toEqual([50, 20]);
    expect(splineSegment(bent, 1)[0]).toEqual([50, 20]);
  });

  it("finds the nearest segment and the insert point on the line", () => {
    const line: Pt[] = [[0, 0], [100, 0], [200, 0]];
    expect(nearestSegment(line, [50, 3], 10)).toBe(0);
    expect(nearestSegment(line, [160, 3], 10)).toBe(1);
    expect(nearestSegment(line, [50, 50], 10)).toBe(-1);
    const hit = nearestOnLine(line, [50, 4], 10);
    expect(hit?.segment).toBe(0);
    expect(Math.abs(hit!.point[1])).toBeLessThan(1.5);
    expect(nearestOnLine(line, [50, 50], 10)).toBeNull();
  });

  it("keeps an excluded segment excluded as both halves after an insert", () => {
    expect(skipAfterInsert([0, 2], 1)).toEqual([0, 3]);
    expect(skipAfterInsert([1], 1)).toEqual([1, 2]);
    expect(skipAfterInsert([0, 3], 1)).toEqual([0, 4]);
    expect(skipAfterInsert([], 0)).toEqual([]);
  });

  it("starts the picked ROI run after the largest gap", () => {
    expect(orderedRoiPick([1, 2, 3], 10)).toEqual([1, 2, 3]);
    expect(orderedRoiPick([8, 9, 0, 1], 10)).toEqual([8, 9, 0, 1]);
    expect(orderedRoiPick([0, 1, 8, 9], 10)).toEqual([8, 9, 0, 1]);
    expect(orderedRoiPick([4], 10)).toEqual([4]);
  });
});
