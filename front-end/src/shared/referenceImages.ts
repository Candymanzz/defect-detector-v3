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
  referenceImagesByCameraId.clear();
  referenceImageVersion += 1;

  bundle.views.forEach((view, viewIndex) => {
    const baseImageUrl =
      createFrameImageUrl(view.frame) ??
      fallbackImageUrlsByCameraId[view.frame.camera_id] ??
      fallbackImageUrlsByCameraId[viewIndex];

    if (baseImageUrl) {
      const referenceImage = {
        imageUrl: versionReferenceImageUrl(baseImageUrl, referenceImageVersion),
        roiPoints: copyRoiPoints(view.interest_polygon_norm),
      };

      referenceImagesByCameraId.set(viewIndex, referenceImage);

      if (view.frame.camera_id === viewIndex || !referenceImagesByCameraId.has(view.frame.camera_id)) {
        referenceImagesByCameraId.set(view.frame.camera_id, referenceImage);
      }
    }
  });

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
  const separator = imageUrl.includes("?") ? "&" : "?";
  return `${imageUrl}${separator}reference_ts=${version}`;
}

function emitReferenceImageChange() {
  for (const listener of listeners) {
    listener();
  }
}
