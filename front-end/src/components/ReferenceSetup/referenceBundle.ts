import type {
  ClientReferenceBundlePayload,
  FpZoneNorm,
  InterestPointNorm,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
} from "../../shared/ws";
import { createRoiFromPolygon, isValidRoiPolygon } from "./referenceRoi";

export function createReferenceBundleFromCameraFrames(
  cameraIds: number[],
  jointCameraId: number,
  framesByCameraId: Record<number, PreviewFramePayload>,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
  jointRoiPolygon: InterestPointNorm[],
  fpZones: FpZoneNorm[],
): ClientReferenceBundlePayload {
  if (cameraIds.length === 0) {
    throw new Error("Configured camera list is empty");
  }

  const jointViewIndex = cameraIds.indexOf(jointCameraId);
  if (jointViewIndex < 0) {
    throw new Error(`Joint ROI camera ${jointCameraId} is not configured`);
  }

  for (const cameraId of cameraIds) {
    if (!framesByCameraId[cameraId]) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    if (!isValidRoiPolygon(roiPolygonsByCameraId[cameraId])) {
      throw new Error(`ROI contour for camera ${cameraId} is missing`);
    }
  }

  for (const zone of fpZones) {
    if (zone.points_norm_heatmap.length < 3) {
      throw new Error(
        `FP zone "${zone.note || zone.id || "unnamed"}" requires at least 3 points`,
      );
    }
  }

  const frames = cameraIds.map((cameraId) => {
    const frame = framesByCameraId[cameraId];

    if (!frame) {
      throw new Error(`Reference frame for camera ${cameraId} is missing`);
    }

    return frame;
  });
  const jointFrame = frames[jointViewIndex] ?? frames[0];
  const productType = jointFrame.detector.product_type || "reference-product";

  for (const frame of frames) {
    if (frame.current.width !== jointFrame.current.width || frame.current.height !== jointFrame.current.height) {
      throw new Error("Reference frames must have the same resolution");
    }
  }

  const views = frames.map((previewFrame, viewIndex) =>
    createReferenceViewForFrame(
      previewFrame,
      cameraIds[viewIndex],
      viewIndex,
      jointViewIndex,
      roiPolygonsByCameraId,
      jointRoiPolygon,
    ),
  );

  return {
    product_type: productType,
    joint_view_index: jointViewIndex,
    heatmap_width: jointFrame.current.width,
    heatmap_height: jointFrame.current.height,
    views,
    fp_zones: fpZones.map((zone) => ({
      ...zone,
      points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({
        x: point.x,
        y: point.y,
      })),
    })),
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
  if (previewFrame.camera_id !== cameraId || previewFrame.current.camera_id !== cameraId) {
    throw new Error(
      `Reference frame camera mismatch: expected ${cameraId}, received ${previewFrame.camera_id}`,
    );
  }

  const roiPolygon = roiPolygonsByCameraId[cameraId];

  if (!isValidRoiPolygon(roiPolygon)) {
    throw new Error(`ROI contour for camera ${cameraId} is missing`);
  }

  const interestPolygonNorm = roiPolygon;
  const roi = createRoiFromPolygon(roiPolygon, previewFrame.current.width, previewFrame.current.height);
  const jointRoi =
    viewIndex === jointViewIndex && isValidRoiPolygon(jointRoiPolygon)
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
