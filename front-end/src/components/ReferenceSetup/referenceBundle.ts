import type {
  ClientReferenceBundlePayload,
  InterestPointNorm,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
} from "../../shared/ws";
import {
  REFERENCE_BUNDLE_VIEW_CAMERA_IDS,
  REFERENCE_JOINT_ROI_CAMERA_ID,
  REFERENCE_REQUIRED_CAMERA_IDS,
} from "./referenceConstants";
import { createRoiFromPolygon, isValidRoiPolygon } from "./referenceRoi";

export function createReferenceBundleFromCameraFrames(
  framesByCameraId: Record<number, PreviewFramePayload>,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
  jointRoiPolygon: InterestPointNorm[],
): ClientReferenceBundlePayload {
  for (const cameraId of REFERENCE_REQUIRED_CAMERA_IDS) {
    if (!framesByCameraId[cameraId]) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    if (!isValidRoiPolygon(roiPolygonsByCameraId[cameraId])) {
      throw new Error(`ROI contour for camera ${cameraId} is missing`);
    }
  }

  if (!isValidRoiPolygon(jointRoiPolygon)) {
    throw new Error(`Joint ROI contour for camera ${REFERENCE_JOINT_ROI_CAMERA_ID} is missing`);
  }

  const frames = REFERENCE_BUNDLE_VIEW_CAMERA_IDS.map((cameraId) => {
    const frame = framesByCameraId[cameraId];

    if (!frame) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    return frame;
  });
  const jointViewIndex = REFERENCE_JOINT_ROI_CAMERA_ID;
  const jointFrame = frames[jointViewIndex] ?? frames[0];
  const productType = jointFrame.detector.product_type || "reference-product";

  for (const frame of frames) {
    if (frame.current.width !== jointFrame.current.width || frame.current.height !== jointFrame.current.height) {
      throw new Error("Reference frames for cameras 0-3 must have the same resolution");
    }
  }

  const views = frames.map((previewFrame, viewIndex) =>
    createReferenceViewForFrame(
      previewFrame,
      REFERENCE_BUNDLE_VIEW_CAMERA_IDS[viewIndex],
      viewIndex,
      jointViewIndex,
      roiPolygonsByCameraId,
      jointRoiPolygon,
    ),
  ) as ClientReferenceBundlePayload["views"];

  return {
    product_type: productType,
    joint_view_index: jointViewIndex,
    heatmap_width: jointFrame.current.width,
    heatmap_height: jointFrame.current.height,
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
  jointRoiPolygon: InterestPointNorm[],
) {
  const roiPolygon = roiPolygonsByCameraId[cameraId];

  if (!isValidRoiPolygon(roiPolygon)) {
    throw new Error(`ROI contour for camera ${cameraId} is missing`);
  }

  const interestPolygonNorm = roiPolygon;
  const roi = createRoiFromPolygon(roiPolygon, previewFrame.current.width, previewFrame.current.height);
  const jointRoi =
    viewIndex === jointViewIndex
      ? createRoiFromPolygon(jointRoiPolygon, previewFrame.current.width, previewFrame.current.height)
      : null;

  return createReferenceView(previewFrame, roi, interestPolygonNorm, jointRoi);
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
