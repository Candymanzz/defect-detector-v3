import { orchestratorApi } from "./api";
import type { ClientReferenceBundlePayload, ShmFrameRefData } from "./ws";

type ReferenceImageListener = () => void;

const referenceImageUrlsByCameraId = new Map<number, string>();
const listeners = new Set<ReferenceImageListener>();

export function commitReferenceBundleImages(bundle: ClientReferenceBundlePayload) {
  referenceImageUrlsByCameraId.clear();

  for (const view of bundle.views) {
    const imageUrl = createFrameImageUrl(view.frame);

    if (imageUrl) {
      referenceImageUrlsByCameraId.set(view.frame.camera_id, imageUrl);
    }
  }

  emitReferenceImageChange();
}

export function getReferenceImageUrl(cameraId?: number) {
  return cameraId === undefined ? undefined : referenceImageUrlsByCameraId.get(cameraId);
}

export function subscribeReferenceImages(listener: ReferenceImageListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
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
