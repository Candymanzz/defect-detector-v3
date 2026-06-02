import type {
  ClientReferenceBundlePayload,
  InterestPointNorm,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
} from "../../shared/ws";
import { REFERENCE_CAMERA_IDS } from "./referenceConstants";
import { createFullRoi, createFullRoiPolygonNorm, createRoiFromPolygon, isValidRoiPolygon } from "./referenceRoi";

export function createReferenceBundleFromCameraFrames(
  framesByCameraId: Record<number, PreviewFramePayload>,
  jointViewIndex: number,
  selectedCameraId: number,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
): ClientReferenceBundlePayload {
  const frames = REFERENCE_CAMERA_IDS.map((cameraId) => {
    const frame = framesByCameraId[cameraId];

    if (!frame) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    return frame;
  });
  const firstFrame = frames[0];
  const productType = firstFrame.detector.product_type || "reference-product";
  const views = frames.map((previewFrame) =>
    createReferenceViewForFrame(previewFrame, jointViewIndex, selectedCameraId, roiPolygonsByCameraId),
  ) as ClientReferenceBundlePayload["views"];

  return {
    product_type: productType,
    joint_view_index: jointViewIndex,
    heatmap_width: firstFrame.current.width,
    heatmap_height: firstFrame.current.height,
    views,
    fp_zones: [],
  };
}

function createReferenceViewForFrame(
  previewFrame: PreviewFramePayload,
  jointViewIndex: number,
  selectedCameraId: number,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
) {
  const selectedRoiPolygon = roiPolygonsByCameraId[previewFrame.camera_id];
  const shouldUseSelectedRoi = previewFrame.camera_id === selectedCameraId && isValidRoiPolygon(selectedRoiPolygon);
  const interestPolygonNorm = shouldUseSelectedRoi
    ? selectedRoiPolygon
    : createFullRoiPolygonNorm(previewFrame.current.width, previewFrame.current.height);
  const roi = shouldUseSelectedRoi
    ? createRoiFromPolygon(selectedRoiPolygon, previewFrame.current.width, previewFrame.current.height)
    : createFullRoi(previewFrame);

  return createReferenceView(previewFrame, roi, interestPolygonNorm, previewFrame.camera_id === jointViewIndex ? roi : null);
}

function createReferenceView(
  previewFrame: PreviewFramePayload,
  interestRoi: PixelRoi,
  interestPolygonNorm: InterestPointNorm[],
  jointRoi: PixelRoi | null,
): ReferenceViewSlot {
  return {
    frame: previewFrame.current,
    interest_roi: interestRoi,
    interest_polygon_norm: interestPolygonNorm,
    joint_roi: jointRoi,
  };
}
