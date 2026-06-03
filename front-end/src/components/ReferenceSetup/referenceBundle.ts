import type {
  ClientReferenceBundlePayload,
  InterestPointNorm,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
} from "../../shared/ws";
import { REFERENCE_BUNDLE_VIEW_CAMERA_IDS } from "./referenceConstants";
import { createFullRoi, createFullRoiPolygonNorm, createRoiFromPolygon, isValidRoiPolygon } from "./referenceRoi";

export function createReferenceBundleFromCameraFrames(
  framesByCameraId: Record<number, PreviewFramePayload>,
  jointViewIndex: number,
  selectedCameraId: number,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
): ClientReferenceBundlePayload {
  const fallbackFrame = framesByCameraId[0];

  if (!fallbackFrame) {
    throw new Error("Reference frame for camera 0 is missing");
  }

  const frames = REFERENCE_BUNDLE_VIEW_CAMERA_IDS.map((cameraId) => {
    return framesByCameraId[cameraId] ?? fallbackFrame;
  });
  const firstFrame = frames[0];
  const productType = firstFrame.detector.product_type || "reference-product";
  const views = frames.map((previewFrame, viewIndex) =>
    createReferenceViewForFrame(previewFrame, viewIndex, jointViewIndex, selectedCameraId, roiPolygonsByCameraId),
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
  viewIndex: number,
  jointViewIndex: number,
  selectedCameraId: number,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
) {
  const selectedRoiPolygon = roiPolygonsByCameraId[viewIndex];
  const shouldUseSelectedRoi = viewIndex === selectedCameraId && isValidRoiPolygon(selectedRoiPolygon);
  const interestPolygonNorm = shouldUseSelectedRoi
    ? selectedRoiPolygon
    : createFullRoiPolygonNorm(previewFrame.current.width, previewFrame.current.height);
  const roi = shouldUseSelectedRoi
    ? createRoiFromPolygon(selectedRoiPolygon, previewFrame.current.width, previewFrame.current.height)
    : createFullRoi(previewFrame);

  return createReferenceView(previewFrame, roi, interestPolygonNorm, viewIndex === jointViewIndex ? roi : null);
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
