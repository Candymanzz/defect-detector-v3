import { describe, expect, it } from "vitest";
import { waitForDetectorSettingsWrites, writeDetectorSettings } from "./detectorSettingsQueue";

describe("detector settings writes", () => {
  it("orders writes for the same camera, waits before reads, and permits other cameras", async () => {
    const calls: string[] = [];
    let finish!: () => void;
    const first = writeDetectorSettings(7, async () => {
      calls.push("first");
      await new Promise<void>((resolve) => { finish = resolve; });
    });
    const second = writeDetectorSettings(7, async () => { calls.push("second"); });
    const read = waitForDetectorSettingsWrites(7).then(() => { calls.push("read"); });
    await writeDetectorSettings(8, async () => { calls.push("other"); });
    expect(calls).toEqual(["first", "other"]);
    finish();
    await Promise.all([first, second, read]);
    expect(calls).toEqual(["first", "other", "second", "read"]);
  });

  it("does not block subsequent updates after a failed write", async () => {
    const failed = writeDetectorSettings(9, async () => { throw new Error("400"); });
    const next = writeDetectorSettings(9, async () => "saved");
    await expect(failed).rejects.toThrow("400");
    await expect(next).resolves.toBe("saved");
    await waitForDetectorSettingsWrites(9);
  });
});
