import { expect, it, vi } from "vitest";
import type { DetectorSettingsPanelHandle } from "./DetectorSettingsPanel";

const hooks = vi.hoisted(() => ({
  effects: [] as (() => unknown)[],
  load: vi.fn().mockRejectedValue(new Error("detector unavailable")),
  save: vi.fn(),
  status: "",
}));
vi.mock("react", async (original) => ({
  ...await original<typeof import("react")>(),
  useEffect: (effect: () => unknown) => { hooks.effects.push(effect); },
  useState: (initial: unknown) => [initial, (next: unknown) => { hooks.status = JSON.stringify(next); }],
  useRef: (initial: unknown) => ({ current: initial }),
  useImperativeHandle: (ref: { current: unknown }, create: () => unknown) => { ref.current = create(); },
}));
vi.mock("../../shared/api", () => ({ orchestratorApi: {
  getCameraDetectorSettings: hooks.load, setCameraDetectorSettings: hooks.save,
} }));
vi.mock("./analysisSettingsEvents", () => ({ subscribeAnalysisSettingsChanged: () => () => {} }));
import { DetectorSettingsPanel } from "./DetectorSettingsPanel";

it("keeps the load failure instead of claiming settings are still loading on save", async () => {
  const ref = { current: null as DetectorSettingsPanelHandle | null };
  const panel = DetectorSettingsPanel as unknown as {
    render: (props: { selectedCameraId: number; hideSaveAction: boolean }, ref: unknown) => unknown;
  };
  panel.render({ selectedCameraId: 0, hideSaveAction: true }, ref);
  hooks.effects.forEach((effect) => effect());
  await vi.waitFor(() => expect(hooks.status).toContain("detector unavailable"));
  await expect(ref.current!.save()).rejects.toThrow("Не удалось загрузить настройки анализа: detector unavailable");
  expect(hooks.status).not.toContain("ещё загружаются");
  expect(hooks.save).not.toHaveBeenCalled();
});
