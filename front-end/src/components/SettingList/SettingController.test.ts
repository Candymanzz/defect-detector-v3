import { beforeEach, describe, expect, it, vi } from "vitest";
import { orchestratorApi } from "../../shared/api";
import { loadSettingData, saveLightMode } from "./SettingController";

vi.mock("../MainOverview/MainController", () => ({ setInspectionHistoryLimit: vi.fn() }));
vi.mock("../../shared/api", () => ({
  orchestratorApi: {
    getLightBrightness: vi.fn(),
    getLightMode: vi.fn(),
    setLightMode: vi.fn(),
    getGeometryRuntime: vi.fn(),
    getFrameArchiveSettings: vi.fn(),
    getAnalysisSettings: vi.fn(),
    getCameraAnalysisSettings: vi.fn(),
    listCameras: vi.fn(),
    getLatestSnapshot: vi.fn(),
  },
}));

describe("shared settings with the C++ detector", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(orchestratorApi.getLightBrightness).mockResolvedValue({
      default_brightness_percent: 51,
      brightness_percent: 51,
      endpoints: [{ id: "camera-0", camera_ids: [0], brightness_percent: 62, mv_le_brightness: 158 }],
    });
    vi.mocked(orchestratorApi.getLightMode).mockResolvedValue({ constant: false, mode: "interval" });
    vi.mocked(orchestratorApi.getGeometryRuntime).mockResolvedValue({
      runtimeOverrides: { max_shift_mm: 0.7 },
      effectiveForNextGeometryInspect: {},
    } as Awaited<ReturnType<typeof orchestratorApi.getGeometryRuntime>>);
    vi.mocked(orchestratorApi.getFrameArchiveSettings).mockResolvedValue({
      max_frames_per_camera: 30,
    } as Awaited<ReturnType<typeof orchestratorApi.getFrameArchiveSettings>>);
    // These routes do not exist on the C++ backend. Calling either must fail the regression test.
    vi.mocked(orchestratorApi.getAnalysisSettings).mockRejectedValue(new Error("HTTP 404"));
    vi.mocked(orchestratorApi.getCameraAnalysisSettings).mockRejectedValue(new Error("HTTP 404"));
  });

  it.each([null, 0])("enables shared controls for camera %s without legacy analysis requests", async (cameraId) => {
    const data = await loadSettingData(cameraId);
    expect(data.status.state).toBe("ready");
    expect(data.form.brightnessPercent).toBe(cameraId === null ? 51 : 62);
    expect(data.form.maxShiftMm).toBe(0.7);
    expect(data.form.savedFramesCount).toBe(30);
    expect(orchestratorApi.getGeometryRuntime).toHaveBeenCalledWith(cameraId);
    expect(orchestratorApi.getAnalysisSettings).not.toHaveBeenCalled();
    expect(orchestratorApi.getCameraAnalysisSettings).not.toHaveBeenCalled();
    expect(orchestratorApi.getLatestSnapshot).not.toHaveBeenCalled();
    vi.mocked(orchestratorApi.setLightMode).mockResolvedValue({ constant: true, mode: "constant" });
    const saved = await saveLightMode(true, data.form, data.analysisProductTypes);
    expect(saved.status.state).toBe("ready");
    expect(saved.form.constantFlashMode).toBe(true);
    expect(saved.form.brightnessPercent).toBe(data.form.brightnessPercent);
  });

  it("keeps a light communication failure visible", async () => {
    vi.mocked(orchestratorApi.getLightMode).mockRejectedValue(new Error("light unavailable"));
    await expect(loadSettingData()).rejects.toThrow("light unavailable");
  });
});
