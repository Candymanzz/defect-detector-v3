import { orchestratorApi } from "./api";
import type {
  ClientReferenceBundlePayload,
  FpZoneNorm,
  InterestPointNorm,
  ShmFrameRefData,
} from "./ws/types";

type ReferenceImageListener = () => void;
export type StoredReferenceImage = {
  imageUrl: string;
  roiPoints: InterestPointNorm[];
  jointRoiPoints?: InterestPointNorm[];
  fpZones?: FpZoneNorm[];
};

const referenceImagesByCameraId = new Map<number, StoredReferenceImage>();
const pendingReferenceBundles = new Map<
  string,
  {
    bundle: ClientReferenceBundlePayload;
    fallbackImageUrlsByCameraId: Record<number, string>;
    roiPointsByCameraId?: Record<number, InterestPointNorm[]>;
    jointCameraId?: number;
    jointRoiPoints?: InterestPointNorm[];
  }
>();
const listeners = new Set<ReferenceImageListener>();
let referenceImageVersion = 0;

export function stageReferenceBundleImages(
  messageId: string,
  bundle: ClientReferenceBundlePayload,
  fallbackImageUrlsByCameraId: Record<number, string> = {},
) {
  pendingReferenceBundles.set(messageId, {
    bundle,
    fallbackImageUrlsByCameraId: { ...fallbackImageUrlsByCameraId },
  });
}

export function resolveReferenceBundleImages(messageId: string, accepted: boolean) {
  const pendingBundle = pendingReferenceBundles.get(messageId);
  if (!pendingBundle) {
    return;
  }
  pendingReferenceBundles.delete(messageId);
  logReferenceBundleAck(messageId, accepted, pendingBundle.bundle);

  if (accepted) {
    commitReferenceBundleImages(
      pendingBundle.bundle,
      pendingBundle.fallbackImageUrlsByCameraId,
      pendingBundle.roiPointsByCameraId,
      pendingBundle.jointCameraId,
      pendingBundle.jointRoiPoints,
    );
  }
}

export function stageReferenceBundleContours(
  messageId: string,
  roiPointsByCameraId: Record<number, InterestPointNorm[]>,
  jointCameraId: number,
  jointRoiPoints: InterestPointNorm[],
) {
  const pendingBundle = pendingReferenceBundles.get(messageId);
  if (!pendingBundle) {
    return;
  }

  pendingBundle.roiPointsByCameraId = Object.fromEntries(
    Object.entries(roiPointsByCameraId).map(([cameraId, points]) => [
      cameraId,
      copyRoiPoints(points),
    ]),
  );
  pendingBundle.jointCameraId = jointCameraId;
  pendingBundle.jointRoiPoints = copyRoiPoints(jointRoiPoints);
}

export function commitReferenceBundleImages(
  bundle: ClientReferenceBundlePayload,
  fallbackImageUrlsByCameraId: Record<number, string> = {},
  roiPointsByCameraId?: Record<number, InterestPointNorm[]>,
  jointCameraId?: number,
  jointRoiPoints?: InterestPointNorm[],
) {
  const nextReferenceImagesByCameraId = new Map(referenceImagesByCameraId);
  const nextReferenceImageVersion = referenceImageVersion + 1;
  const updatedCameraIds = new Set<number>();

  bundle.views.forEach((view, viewIndex) => {
    const cameraId = view.frame.camera_id;
    updatedCameraIds.add(cameraId);
    const baseImageUrl =
      fallbackImageUrlsByCameraId[cameraId] ??
      createFrameImageUrl(view.frame);

    if (baseImageUrl) {
      const referenceImage = {
        imageUrl: versionReferenceImageUrl(baseImageUrl, nextReferenceImageVersion),
        roiPoints: copyRoiPoints(roiPointsByCameraId?.[cameraId] ?? view.interest_polygon_norm),
        jointRoiPoints:
          cameraId === jointCameraId && jointRoiPoints
            ? copyRoiPoints(jointRoiPoints)
            : viewIndex === bundle.joint_view_index && view.joint_roi
              ? createNormalizedRoiPolygon(view.joint_roi, view.frame.width, view.frame.height)
              : undefined,
        fpZones: copyFpZones(bundle.fp_zones),
      };

      nextReferenceImagesByCameraId.set(cameraId, referenceImage);
    }
  });

  if (nextReferenceImagesByCameraId.size === 0) {
    return;
  }

  referenceImagesByCameraId.forEach((referenceImage, cameraId) => {
    if (!updatedCameraIds.has(cameraId)) {
      return;
    }
    if (
      referenceImage.imageUrl.startsWith("blob:") &&
      !Array.from(nextReferenceImagesByCameraId.values()).some(
        (nextReferenceImage) => nextReferenceImage.imageUrl.startsWith(referenceImage.imageUrl),
      )
    ) {
      URL.revokeObjectURL(referenceImage.imageUrl.split("?")[0]);
    }
  });
  referenceImagesByCameraId.clear();
  nextReferenceImagesByCameraId.forEach((referenceImage, cameraId) => {
    referenceImagesByCameraId.set(cameraId, referenceImage);
  });
  referenceImageVersion = nextReferenceImageVersion;

  emitReferenceImageChange();
}

function logReferenceBundleAck(messageId: string, accepted: boolean, bundle: ClientReferenceBundlePayload) {
  console.info("[reference] bundle ack", {
    messageId,
    accepted,
    productType: bundle.product_type,
    cameras: bundle.views.map((view) => view.frame.camera_id),
    frames: bundle.views.map((view) => ({
      cameraId: view.frame.camera_id,
      frameId: view.frame.frame_id,
      hasJointRoi: Boolean(view.joint_roi),
    })),
    jointViewIndex: bundle.joint_view_index,
    jointCameraId: bundle.views[bundle.joint_view_index]?.frame.camera_id,
  });
}

export function getReferenceImageUrl(cameraId?: number) {
  return getReferenceImage(cameraId)?.imageUrl;
}

export function getReferenceImage(cameraId?: number) {
  return cameraId === undefined ? undefined : referenceImagesByCameraId.get(cameraId);
}

export function subscribeReferenceImages(listener: ReferenceImageListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function copyRoiPoints(points: InterestPointNorm[]) {
  return points.map((point) => ({
    x: point.x,
    y: point.y,
  }));
}

export function updateReferenceFpZones(cameraIds: number[], zones: FpZoneNorm[]) {
  let changed = false;
  for (const cameraId of cameraIds) {
    const referenceImage = referenceImagesByCameraId.get(cameraId);
    if (!referenceImage) continue;
    referenceImagesByCameraId.set(cameraId, {
      ...referenceImage,
      fpZones: copyFpZones(zones),
    });
    changed = true;
  }
  if (changed) emitReferenceImageChange();
}

function copyFpZones(zones: FpZoneNorm[]) {
  return zones.map((zone) => ({
    ...zone,
    points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({
      x: point.x,
      y: point.y,
    })),
  }));
}

function createNormalizedRoiPolygon(
  roi: { x: number; y: number; width: number; height: number },
  frameWidth: number,
  frameHeight: number,
) {
  if (frameWidth <= 0 || frameHeight <= 0) {
    return undefined;
  }

  const left = clamp01(roi.x / frameWidth);
  const top = clamp01(roi.y / frameHeight);
  const right = clamp01((roi.x + roi.width) / frameWidth);
  const bottom = clamp01((roi.y + roi.height) / frameHeight);

  return [
    { x: left, y: top },
    { x: right, y: top },
    { x: right, y: bottom },
    { x: left, y: bottom },
  ];
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}

function createFrameImageUrl(frame: ShmFrameRefData) {
  const imagePath = frame.http_path;

  if (imagePath) {
    return orchestratorApi.imageUrl(imagePath, frame.frame_id);
  }

  return undefined;
}

function versionReferenceImageUrl(imageUrl: string, version: number) {
  if (imageUrl.startsWith("blob:")) {
    return imageUrl;
  }

  const separator = imageUrl.includes("?") ? "&" : "?";
  return `${imageUrl}${separator}reference_ts=${version}`;
}

function emitReferenceImageChange() {
  for (const listener of listeners) {
    listener();
  }
}
