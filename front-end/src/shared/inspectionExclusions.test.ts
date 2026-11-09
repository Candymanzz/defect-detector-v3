import { describe, expect, it } from "vitest";
import { copyExclusions, exclusionPointFromImage, validateExclusions } from "./inspectionExclusions";
import type { Exclusions } from "./inspectionExclusions";

const triangle: Exclusions = [[[0, 0], [1, 0], [0, 1]]];

describe("inspection exclusion geometry", () => {
  it("accepts empty, overlapping, boundary and clockwise polygons and copies tuples deeply", () => {
    expect(validateExclusions([])).toBeNull();
    expect(validateExclusions([...triangle, [...triangle[0]].reverse()])).toBeNull();
    const copy = copyExclusions(triangle);
    copy[0][0][0] = .5;
    expect(triangle[0][0][0]).toBe(0);
  });

  it.each([
    null, {}, Array(33).fill(triangle[0]), [[...triangle[0], ...Array(126).fill([.5, .5])]],
    [[[0, 0], [1, 1]]], [[[0, 0], [1, 1], [2, 1]]], [[[0, 0], [1, 1], [NaN, 0]]],
    [[[0, 0], [1, 1], [Infinity, 0]]], [[{ x: 0, y: 0 }, [1, 0], [0, 1]]],
    [[[0, 0], [1, 1], [.5, .5]]], [[[0, 0], [1, 0], [1, 0], [0, 1]]],
    // Self intersection with nonzero signed area, and nonadjacent edge touching.
    [[[0, 0], [1, .8], [0, 1], [.8, 0]]],
    [[[0, 0], [1, 0], [.5, .5], [1, 1], [0, 1], [.5, .5]]],
  ].map((value) => ({ value })))("rejects invalid geometry $value", ({ value }) => {
    expect(validateExclusions(value)).not.toBeNull();
  });

  it("accounts for letterboxing and source W-1/H-1 under scaling and pan", () => {
    const rect = { left: 40, top: 80, width: 800, height: 600 };
    // 400x200 rendered at 2x: image begins at y=180, with 100px margins.
    expect(exclusionPointFromImage(40, 80, rect, 400, 200)).toBeNull();
    expect(exclusionPointFromImage(40, 180, rect, 400, 200)).toEqual([0, 0]);
    expect(exclusionPointFromImage(838, 578, rect, 400, 200)).toEqual([1, 1]);
    expect(exclusionPointFromImage(439, 379, rect, 400, 200)).toEqual([.5, .5]);
    expect(exclusionPointFromImage(900, 379, rect, 400, 200)).toBeNull();
    expect(exclusionPointFromImage(40, 180, rect, 1, 200)).toBeNull();
  });
});
