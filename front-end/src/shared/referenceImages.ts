import { orchestratorApi } from "./api";
import type { ClientReferenceBundlePayload, InterestPointNorm, ShmFrameRefData } from "./ws";

type ReferenceImageListener = () => void;
export type StoredReferenceImage = {
  imageUrl: string;
  roiPoints: InterestPointNorm[];
};

const referenceImagesByCameraId = new Map<number, StoredReferenceImage>();
const listeners = new Set<ReferenceImageListener>();

export function commitReferenceBundleImages(
  bundle: ClientReferenceBundlePayload,
  fallbackImageUrlsByCameraId: Record<number, string> = {},
) {
  referenceImagesByCameraId.clear();

  bundle.views.forEach((view, viewIndex) => {
    const imageUrl =
      createFrameImageUrl(view.frame) ??
      fallbackImageUrlsByCameraId[view.frame.camera_id] ??
      fallbackImageUrlsByCameraId[viewIndex];

    if (imageUrl) {
      const referenceImage = {
        imageUrl,
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

function emitReferenceImageChange() {
  for (const listener of listeners) {
    listener();
  }
}
