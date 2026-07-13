import { describe, expect, it } from "vitest";

import type { PreviewFramePayload } from "../../shared/ws";
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

describe("createReferenceBundleFromCameraFrames", () => {
  it("builds bundle for configured cameras", () => {
    const bundle = createReferenceBundleFromCameraFrames(
      [0, 1],
      1,
      { 0: previewFrame(0), 1: previewFrame(1) },
      { 0: roi, 1: roi },
      roi,
      [],
    );

    expect(bundle.product_type).toBe("bench");
    expect(bundle.joint_view_index).toBe(1);
    expect(bundle.views).toHaveLength(2);
    expect(bundle.views[1].joint_roi).not.toBeNull();
  });

  it("rejects missing roi contour", () => {
    expect(() =>
      createReferenceBundleFromCameraFrames(
        [0],
        0,
        { 0: previewFrame(0) },
        {},
        roi,
        [],
      ),
    ).toThrow(/ROI contour/);
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
        roi,
        [],
      ),
    ).toThrow(/same resolution/);
  });
});
