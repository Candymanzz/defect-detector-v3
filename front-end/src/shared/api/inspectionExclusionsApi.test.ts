import { afterEach, describe, expect, it, vi } from "vitest";
import { orchestratorApi } from "./orchestratorApi";
import type { Exclusions } from "../inspectionExclusions";

afterEach(() => vi.unstubAllGlobals());

describe("inspection exclusion API contract", () => {
  it("sends partial camera updates and preserves explicit empty overrides under analysis.settings", async () => {
    vi.stubGlobal("window", globalThis);
    const fetchMock = vi.fn(async () => new Response('{"settings":{"exclusions":[]}}', { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const exclusions: Exclusions = [[[.2, .3], [.4, .3], [.4, .6]]];
    await orchestratorApi.setCameraDetectorSettings(3, { exclusions });
    await orchestratorApi.setCameraDetectorSettings(3, { threshold: .6 });
    await orchestratorApi.testAnalyzePinnedFrame(3, "pin", "42", { analysis: { settings: { exclusions: [] } } });
    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit]>;
    expect(new URL(calls[0][0]).pathname).toBe("/api/orchestrator/detector-settings/camera/3");
    expect(calls[0][1].method).toBe("PUT");
    expect(JSON.parse(String(calls[0][1].body))).toEqual({ exclusions });
    expect(JSON.parse(String(calls[1][1].body))).toEqual({ threshold: .6 });
    const test = JSON.parse(String(calls[2][1].body));
    expect(test.temporarySettings.analysis.settings.exclusions).toEqual([]);
    expect(test.source).toBe("pin");
    expect(test.pinId).toBe("pin");
  });
});
