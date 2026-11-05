import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createReferenceBundleFromCameraFrames } from "../components/ReferenceSetup/referenceBundle";
import type { PreviewFramePayload } from "./ws/types";

const { listCases } = vi.hoisted(() => ({ listCases: vi.fn() }));
vi.mock("./api", () => ({
  HttpError: class extends Error {},
  orchestratorApi: { getLearnedNormals: listCases, learnedNormalImageUrl: (id: string) => `/norms/${id}` },
}));
let refs: typeof import("./referenceImages");
const roi = [{ x: .1, y: .1 }, { x: .9, y: .1 }, { x: .9, y: .9 }, { x: .1, y: .9 }];
const frame: PreviewFramePayload = {
  camera_id: 0, frame_id: "1", session_state: "READY", server_ts_ms: 1, detector: { product_type: "bucket" },
  current: { camera_id: 0, frame_id: "1", shm_name: "/ref", width: 100, height: 80, stride: 300,
    shm_offset: 0, pixel_format: "bgr_u8", channels: 3 },
};

beforeEach(async () => {
  vi.resetModules();listCases.mockReset();
  let nextUrl = 0;
  vi.spyOn(URL, "createObjectURL").mockImplementation(() => `blob:norm-${++nextUrl}`);
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
  vi.stubGlobal("fetch", vi.fn(async () => new Response(new Blob(["crop"], { type: "image/png" }))));
  refs = await import("./referenceImages");
  const bundle = createReferenceBundleFromCameraFrames([0], 0, { 0: frame }, { 0: roi }, [], []);
  refs.commitReferenceBundleImages(bundle, { 0: "reference-image" });
  await refs.attachLearnedCasesToActiveReference(0, [{ id: "old", product_type: "bucket#cam=0" }]);
});
afterEach(() => { vi.restoreAllMocks();vi.unstubAllGlobals(); });

it("removes stale learned crops after a full application reset while preserving references and contours", async () => {
  const reference = refs.getReferenceImage(0);
  const archivedImages = refs.getArchivedReferenceGroups()[0].images;
  listCases.mockResolvedValue({ cases: [] });
  await refs.reconcileLearnedCasesWithDetector();
  const archive = refs.getArchivedReferenceGroups()[0];
  expect(archive.learnedCaseIdsByCameraId[0]).toEqual([]);
  expect(archive.learnedCasesByCameraId[0]).toEqual([]);
  expect(refs.getReferenceImage(0)).toEqual(reference);
  expect(archive.images).toEqual(archivedImages);
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:norm-1");
});

it("preserves valid norms across reconnect or browser reload within the running application", async () => {
  listCases.mockResolvedValue({ cases: [{ id: "old" }] });
  await refs.reconcileLearnedCasesWithDetector();
  expect(refs.getArchivedReferenceGroups()[0].learnedCaseIdsByCameraId[0]).toEqual(["old"]);
  expect(URL.revokeObjectURL).not.toHaveBeenCalled();
});

it("keeps cached norms when the detector is unavailable or the response is malformed", async () => {
  listCases.mockRejectedValue(new Error("offline"));
  await expect(refs.reconcileLearnedCasesWithDetector()).rejects.toThrow("offline");
  listCases.mockResolvedValue({});
  await expect(refs.reconcileLearnedCasesWithDetector()).rejects.toThrow();
  expect(refs.getArchivedReferenceGroups()[0].learnedCaseIdsByCameraId[0]).toEqual(["old"]);
});

it("preserves new crops added while the reconciliation request is pending", async () => {
  let finish!: (value: { cases: [] }) => void;
  listCases.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const pending = refs.reconcileLearnedCasesWithDetector();
  await vi.waitFor(() => expect(listCases).toHaveBeenCalledOnce());
  await refs.attachLearnedCasesToActiveReference(0, [{ id: "new", product_type: "bucket#cam=0" }]);
  finish({ cases: [] });await pending;
  expect(refs.getArchivedReferenceGroups()[0].learnedCaseIdsByCameraId[0]).toEqual(["new"]);
  expect(refs.getArchivedReferenceGroups()[0].learnedCasesByCameraId[0].map((item) => item.id)).toEqual(["new"]);
});

it("waits for persisted reference hydration and saves the cleared norm list after reconnect", async () => {
  type State = { archives: Array<{ learnedCaseIdsByCameraId: Record<string, string[]> }> };
  let stored: State | undefined;
  const database = {
    close() {},
    transaction() {
      const transaction = {
        oncomplete: undefined as (() => void) | undefined,
        objectStore() {
          return {
            put(value: State) {
              stored = structuredClone(value);
              queueMicrotask(() => transaction.oncomplete?.());
            },
            get() {
              const request = { result: undefined as State | undefined, onsuccess: undefined as (() => void) | undefined };
              queueMicrotask(() => { request.result = structuredClone(stored);request.onsuccess?.(); });
              return request;
            },
          };
        },
      };
      return transaction;
    },
  };
  vi.stubGlobal("indexedDB", { open() {
    const request = { result: database, onsuccess: undefined as (() => void) | undefined };
    queueMicrotask(() => request.onsuccess?.());
    return request;
  } });
  await refs.attachLearnedCasesToActiveReference(0, [{ id: "persisted", product_type: "bucket#cam=0" }]);
  await vi.waitFor(() => expect(stored?.archives[0].learnedCaseIdsByCameraId[0]).toEqual(["old", "persisted"]));

  vi.resetModules();
  const restored = await import("./referenceImages");
  listCases.mockResolvedValue({ cases: [] });
  await restored.reconcileLearnedCasesWithDetector();
  expect(restored.getReferenceImage(0)?.frame.width).toBe(100);
  expect(restored.getReferenceImage(0)?.roiPoints).toEqual(roi);
  expect(restored.getArchivedReferenceGroups()[0].learnedCaseIdsByCameraId[0]).toEqual([]);
  await vi.waitFor(() => expect(stored?.archives[0].learnedCaseIdsByCameraId[0]).toEqual([]));
});
