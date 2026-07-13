import { describe, expect, it } from "vitest";

import { compareFrameIds } from "./frameIds";

describe("compareFrameIds", () => {
  it("compares numeric frame ids as bigint", () => {
    expect(compareFrameIds("100", "99")).toBe(1);
    expect(compareFrameIds("42", "42")).toBe(0);
    expect(compareFrameIds("7", "1000")).toBe(-1);
  });

  it("falls back to localeCompare for non-numeric ids", () => {
    expect(compareFrameIds("frame-2", "frame-10")).toBeLessThan(0);
    expect(compareFrameIds("abc", "abc")).toBe(0);
  });
});
