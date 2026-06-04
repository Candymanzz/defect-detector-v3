import type {
  ClientReferenceBundlePayload,
  InterestPointNorm,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
} from "../../shared/ws";
import { REFERENCE_BUNDLE_VIEW_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";
import { createRoiFromPolygon, isValidRoiPolygon } from "./referenceRoi";

export function createReferenceBundleFromCameraFrames(
  framesByCameraId: Record<number, PreviewFramePayload>,
  jointViewIndex: number,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
): ClientReferenceBundlePayload {
  for (const cameraId of REFERENCE_REQUIRED_CAMERA_IDS) {
    if (!framesByCameraId[cameraId]) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    if (!isValidRoiPolygon(roiPolygonsByCameraId[cameraId])) {
      throw new Error(`ROI contour for camera ${cameraId} is missing`);
    }
  }

  const frames = REFERENCE_BUNDLE_VIEW_CAMERA_IDS.map((cameraId) => {
    const frame = framesByCameraId[cameraId];

    if (!frame) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    return frame;
  });
  const firstFrame = frames[0];
  const productType = firstFrame.detector.product_type || "reference-product";
  const views = frames.map((previewFrame, viewIndex) =>
    createReferenceViewForFrame(
      previewFrame,
      REFERENCE_BUNDLE_VIEW_CAMERA_IDS[viewIndex],
      viewIndex,
      jointViewIndex,
      roiPolygonsByCameraId,
    ),
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
  cameraId: number,
  viewIndex: number,
  jointViewIndex: number,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
) {
  const roiPolygon = roiPolygonsByCameraId[cameraId];

  if (!isValidRoiPolygon(roiPolygon)) {
    throw new Error(`ROI contour for camera ${cameraId} is missing`);
  }

  const interestPolygonNorm = roiPolygon;
  const roi = createRoiFromPolygon(roiPolygon, previewFrame.current.width, previewFrame.current.height);

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
