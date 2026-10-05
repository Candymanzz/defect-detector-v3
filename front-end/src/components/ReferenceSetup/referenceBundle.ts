import type {
  ClientReferenceBundlePayload,
  FpZoneNorm,
  InterestPointNorm,
  PixelRoi,
  PreviewFramePayload,
  ReferenceViewSlot,
} from "../../shared/ws";
import { createRoiFromPolygon, isValidJointRoiPolygon, isValidRoiPolygon } from "./referenceRoi";

export function createReferenceBundleFromCameraFrames(
  cameraIds: number[],
  jointCameraId: number,
  framesByCameraId: Record<number, PreviewFramePayload>,
  roiPolygonsByCameraId: Record<number, InterestPointNorm[]>,
  jointRoiPolygon: InterestPointNorm[],
  fpZones: FpZoneNorm[],
): ClientReferenceBundlePayload {
  if (cameraIds.length === 0) {
    throw new Error("Список настроенных камер пуст");
  }

  const jointViewIndex = cameraIds.indexOf(jointCameraId);
  if (jointViewIndex < 0) {
    throw new Error(`Камера ${jointCameraId} для joint ROI не настроена`);
  }

  for (const cameraId of cameraIds) {
    if (!framesByCameraId[cameraId]) {
      throw new Error(`Нет эталонного кадра для камеры ${cameraId}`);
    }

    if (!isValidRoiPolygon(roiPolygonsByCameraId[cameraId])) {
      throw new Error(`Нет контура ROI для камеры ${cameraId}`);
    }
  }

  for (const zone of fpZones) {
    if (zone.points_norm_heatmap.length < 3) {
      throw new Error(
        `FP zone "${zone.note || zone.id || "unnamed"}" requires at least 3 points`,
      );
    }
  }

  if (jointRoiPolygon.length > 0 && !isValidJointRoiPolygon(jointRoiPolygon)) {
    throw new Error(
      "Joint ROI должен быть ориентированным прямоугольником: протяните ось вдоль шва и задайте ширину",
    );
  }

  const frames = cameraIds.map((cameraId) => {
    const frame = framesByCameraId[cameraId];

    if (!frame) {
      throw new Error(`Нет эталонного кадра для камеры ${cameraId}`);
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
      `Несовпадение камеры эталонного кадра: ожидалась ${cameraId}, получена ${previewFrame.camera_id}`,
    );
  }

  const roiPolygon = roiPolygonsByCameraId[cameraId];

  if (!isValidRoiPolygon(roiPolygon)) {
    throw new Error(`Нет контура ROI для камеры ${cameraId}`);
  }

  const interestPolygonNorm = roiPolygon;
  const roi = createRoiFromPolygon(roiPolygon, previewFrame.current.width, previewFrame.current.height);
  const jointRoi =
    viewIndex === jointViewIndex && isValidJointRoiPolygon(jointRoiPolygon)
      ? createRoiFromPolygon(jointRoiPolygon, previewFrame.current.width, previewFrame.current.height)
      : null;
  const jointRoiPolygonNorm =
    viewIndex === jointViewIndex && isValidJointRoiPolygon(jointRoiPolygon) ? jointRoiPolygon : null;

  return createReferenceView(previewFrame, roi, interestPolygonNorm, jointRoi, jointRoiPolygonNorm);
}

function createReferenceView(
  previewFrame: PreviewFramePayload,
  interestRoi: PixelRoi,
  interestPolygonNorm: InterestPointNorm[],
  jointRoi: PixelRoi | null,
  jointRoiPolygonNorm: InterestPointNorm[] | null,
): ReferenceViewSlot {
  return {
    frame: previewFrame.current,
    interest_roi: interestRoi,
    interest_polygon_norm: interestPolygonNorm,
    joint_roi: jointRoi,
    ...(jointRoiPolygonNorm && isValidJointRoiPolygon(jointRoiPolygonNorm)
      ? { joint_roi_polygon_norm: jointRoiPolygonNorm }
      : {}),
  };
}
