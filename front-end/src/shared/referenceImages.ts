import { orchestratorApi } from "./api";
import type { ClientReferenceBundlePayload, InterestPointNorm, ShmFrameRefData } from "./ws";

type ReferenceImageListener = () => void;
export type StoredReferenceImage = {
  imageUrl: string;
  roiPoints: InterestPointNorm[];
};

const referenceImagesByCameraId = new Map<number, StoredReferenceImage>();
const listeners = new Set<ReferenceImageListener>();
let referenceImageVersion = 0;

export function commitReferenceBundleImages(
  bundle: ClientReferenceBundlePayload,
  fallbackImageUrlsByCameraId: Record<number, string> = {},
) {
  const nextReferenceImagesByCameraId = new Map<number, StoredReferenceImage>();
  const nextReferenceImageVersion = referenceImageVersion + 1;

  bundle.views.forEach((view) => {
    const cameraId = view.frame.camera_id;
    const baseImageUrl =
      fallbackImageUrlsByCameraId[cameraId] ??
      createFrameImageUrl(view.frame);

    if (baseImageUrl) {
      const referenceImage = {
        imageUrl: versionReferenceImageUrl(baseImageUrl, nextReferenceImageVersion),
        roiPoints: copyRoiPoints(view.interest_polygon_norm),
      };

      nextReferenceImagesByCameraId.set(cameraId, referenceImage);
    }
  });

  if (nextReferenceImagesByCameraId.size === 0) {
    return;
  }

  referenceImagesByCameraId.forEach((referenceImage) => {
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
