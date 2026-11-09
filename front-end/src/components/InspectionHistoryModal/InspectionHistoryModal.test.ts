import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InspectionHistoryModal } from "./InspectionHistoryModal";
import type { InspectionHistoryItem } from "../MainOverview/type";

const references = vi.hoisted(() => new Map<number, { imageUrl: string }>());
vi.mock("../../shared/referenceImages", () => ({
  getReferenceImage: (cameraId: number) => references.get(cameraId),
}));

function frame(cameraId: number, frameId = "42"): InspectionHistoryItem {
  return {
    frameId, inspectionId: frameId, result: "pass",
    inspectResult: {
      camera_id: cameraId, frame_id: frameId, session_state: "READY",
      http_path: `/api/frame-archive/cameras/${cameraId}/frames/${frameId}/frame.jpg`,
      current: { camera_id: cameraId, frame_id: frameId, shm_name: "", width: 400, height: 300,
        stride: 1200, shm_offset: 0, pixel_format: "bgr_u8", channels: 3 },
      heatmap: { width: 400, height: 300, channels: 1, pixel_format: "gray_u8", http_path: "/unused.u8" },
      detector: {}, active_reference_view_index: 0, fp_zones: [], server_ts_ms: 100,
    },
  };
}

function render(results: InspectionHistoryItem[]) {
  return renderToStaticMarkup(createElement(InspectionHistoryModal, {
    inspectionId: results[0].inspectionId, results, onClose: () => undefined,
  }));
}

function imageSources(markup: string) {
  return [...markup.matchAll(/<img\b[^>]*\bsrc="([^"]+)"/g)].map(match => match[1]);
}

beforeEach(() => {
  references.clear();
  for (let id = 0; id < 5; id++) references.set(id, { imageUrl: `/reference-${id}.jpg` });
});

describe("grouped inspection images", () => {
  it("shows each of five cameras' actual frame beneath its heatmap, alongside its reference", () => {
    const items = Array.from({ length: 5 }, (_, id) => frame(id));
    const cards = [...render(items).matchAll(/<article\b[\s\S]*?<\/article>/g)].map(match => match[0]);
    expect(cards).toHaveLength(5);
    cards.forEach((card, id) => {
      const sources = imageSources(card);
      expect(sources).toHaveLength(2);
      expect(sources[0]).toBe(`/reference-${id}.jpg`);
      expect(sources[1]).toContain(`/api/frame-archive/cameras/${id}/frames/42/frame.jpg`);
      expect(card).toContain("Кадр инспекции с тепловой картой");
    });
    const previous = imageSources(render([frame(0, "41")]));
    expect(previous[1]).toContain("/frames/41/frame.jpg");
    expect(previous[1]).not.toContain("/frames/42/");
  });

  it("uses the immutable artifact frame when a live result contains a bundle", () => {
    const item = frame(2);
    item.inspectResult.http_path = "/api/ui/cameras/2/current.jpg";
    item.inspectResult.artifact_bundle_id = "inspection-42-camera-2";
    const sources = imageSources(render([item]));
    expect(sources[1]).toContain("/api/inspection-artifacts/inspection-42-camera-2/frame.jpg");
    expect(sources[1]).not.toContain("current.jpg");
  });

  it("shows the actual frame while its deferred heatmap is absent", () => {
    const item = frame(1);
    item.inspectResult.heatmap = null;
    const markup = render([item]);
    expect(imageSources(markup)[1]).toContain("/cameras/1/frames/42/frame.jpg");
    expect(markup).toContain("Кадр инспекции</figcaption>");
    expect(markup).not.toContain("Тепловая карта отсутствует");
  });

  it("keeps missing reference and missing inspection frame distinct", () => {
    references.delete(0);
    const item = frame(0);
    const missingReference = render([item]);
    expect(missingReference).toContain("Эталон недоступен");
    expect(imageSources(missingReference)).toHaveLength(1);
    expect(imageSources(missingReference)[0]).toContain("/frames/42/frame.jpg");

    references.set(0, { imageUrl: "/reference-0.jpg" });
    item.inspectResult.http_path = undefined;
    const missingFrame = render([item]);
    expect(imageSources(missingFrame)).toEqual(["/reference-0.jpg"]);
    expect(missingFrame).toContain("Кадр инспекции недоступен");
  });
});
