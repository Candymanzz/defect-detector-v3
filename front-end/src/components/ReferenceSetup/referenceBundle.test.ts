import { describe, expect, it } from "vitest";

import type { PreviewFramePayload } from "../../shared/ws";
import { createCirclePolygonFromRadius } from "../RoiContourEditor/circleRoi";
import { createOrientedRectFromAxis } from "../RoiContourEditor/orientedRectRoi";
import { createReferenceBundleFromCameraFrames } from "./referenceBundle";

function previewFrame(cameraId: number): PreviewFramePayload {
  return {
    camera_id: cameraId,
    frame_id: `frame-${cameraId}`,
    session_state: "ready",
    current: {
      camera_id: cameraId,
      frame_id: `frame-${cameraId}`,
      shm_name: `/cam-${cameraId}`,
      width: 100,
      height: 80,
      stride: 300,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
    },
    detector: { product_type: "bench" },
    server_ts_ms: 1,
  };
}

const roi = [
  { x: 0.1, y: 0.1 },
  { x: 0.9, y: 0.1 },
  { x: 0.9, y: 0.9 },
  { x: 0.1, y: 0.9 },
];

const jointRoi = createOrientedRectFromAxis({ x: 0.2, y: 0.5 }, { x: 0.8, y: 0.5 }, 0.04);

describe("createReferenceBundleFromCameraFrames", () => {
  it("builds bundle for configured cameras", () => {
    const bundle = createReferenceBundleFromCameraFrames(
      [0, 1],
      1,
      { 0: previewFrame(0), 1: previewFrame(1) },
      { 0: roi, 1: roi },
      jointRoi,
      [],
    );

    expect(bundle.product_type).toBe("bench");
    expect(bundle.joint_view_index).toBe(1);
    expect(bundle.views).toHaveLength(2);
    expect(bundle.views[1].joint_roi).not.toBeNull();
    expect(bundle.views[1].joint_roi_polygon_norm).toHaveLength(4);
  });

  it("accepts circular ROI from radius as interest_polygon_norm for inspect services", () => {
    const circle = createCirclePolygonFromRadius(
      { x: 0.5, y: 0.5 },
      { x: 0.72, y: 0.5 },
      100,
      80,
      48,
    );
    expect(circle.length).toBeGreaterThanOrEqual(3);

    const bundle = createReferenceBundleFromCameraFrames(
      [0],
      0,
      { 0: previewFrame(0) },
      { 0: circle },
      jointRoi,
      [],
    );

    expect(bundle.views[0].interest_polygon_norm).toHaveLength(circle.length);
    expect(bundle.views[0].interest_roi.width).toBeGreaterThan(1);
    expect(bundle.views[0].interest_roi.height).toBeGreaterThan(1);
  });

  it("rejects crooked joint polygon", () => {
    expect(() =>
      createReferenceBundleFromCameraFrames(
        [0],
        0,
        { 0: previewFrame(0) },
        { 0: roi },
        [
          { x: 0.1, y: 0.1 },
          { x: 0.9, y: 0.2 },
          { x: 0.7, y: 0.8 },
          { x: 0.2, y: 0.9 },
          { x: 0.15, y: 0.5 },
        ],
        [],
      ),
    ).toThrow(/ориентированным прямоугольником/);
  });

  it("rejects missing roi contour", () => {
    expect(() =>
      createReferenceBundleFromCameraFrames(
        [0],
        0,
        { 0: previewFrame(0) },
        {},
        jointRoi,
        [],
      ),
    ).toThrow(/контура ROI/);
  });

  it("rejects resolution mismatch", () => {
    const other = previewFrame(1);
    other.current.height = 90;

    expect(() =>
      createReferenceBundleFromCameraFrames(
        [0, 1],
        0,
        { 0: previewFrame(0), 1: other },
        { 0: roi, 1: roi },
        jointRoi,
        [],
      ),
    ).toThrow(/same resolution/);
  });
});
