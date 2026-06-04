import { useCallback, useState } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { PreviewFramePayload } from "../../shared/ws";
import { REFERENCE_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";

export function useReferenceFrames() {
  const [imageUrl, setImageUrl] = useState<string>();
  const [liveFramesByCameraId, setLiveFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [liveImageUrlsByCameraId, setLiveImageUrlsByCameraId] = useState<Record<number, string>>({});
  const [framesByCameraId, setFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<Record<number, string>>({});
  const cameraSlots = REFERENCE_CAMERA_IDS.map((cameraId) => ({
    cameraId,
    frame: framesByCameraId[cameraId],
    imageUrl: imageUrlsByCameraId[cameraId],
  }));
  const hasRequiredReferenceFrames = REFERENCE_REQUIRED_CAMERA_IDS.every((cameraId) => framesByCameraId[cameraId]);

  const refreshLatestImages = useCallback(async (cameraId?: number) => {
    if (cameraId !== undefined) {
      const liveFrame = liveFramesByCameraId[cameraId];
      const liveImageUrl = liveImageUrlsByCameraId[cameraId];

      if (!liveFrame || !liveImageUrl) {
        return false;
      }

      setFramesByCameraId((prevFrames) => ({
        ...prevFrames,
        [cameraId]: liveFrame,
      }));
      setImageUrlsByCameraId((prevImageUrls) => ({
        ...prevImageUrls,
        [cameraId]: liveImageUrl,
      }));
      setImageUrl(liveImageUrl);

      return true;
    }

    const nextFramesByCameraId: Record<number, PreviewFramePayload> = {};
    const nextImageUrlsByCameraId: Record<number, string> = {};

    for (const cameraId of REFERENCE_CAMERA_IDS) {
      const liveFrame = liveFramesByCameraId[cameraId];
      const liveImageUrl = liveImageUrlsByCameraId[cameraId];

      if (liveFrame && liveImageUrl) {
        nextFramesByCameraId[cameraId] = liveFrame;
        nextImageUrlsByCameraId[cameraId] = liveImageUrl;
      }
    }

    if (Object.keys(nextFramesByCameraId).length === 0) {
      return false;
    }

    setFramesByCameraId(nextFramesByCameraId);
    setImageUrlsByCameraId(nextImageUrlsByCameraId);
    setImageUrl(nextImageUrlsByCameraId[REFERENCE_CAMERA_IDS[0]]);

    return true;
  }, [liveFramesByCameraId, liveImageUrlsByCameraId]);

  const handlePreviewFrame = useCallback((previewFrame: PreviewFramePayload) => {
    const imagePath = previewFrame.http_path ?? previewFrame.current.http_path;
    const nextImageUrl = imagePath ? orchestratorApi.imageUrl(imagePath, previewFrame.frame_id) : undefined;

    setLiveFramesByCameraId((prevFrames) => ({
      ...prevFrames,
      [previewFrame.camera_id]: previewFrame,
    }));
    if (nextImageUrl) {
      setLiveImageUrlsByCameraId((prevImageUrls) => ({
        ...prevImageUrls,
        [previewFrame.camera_id]: nextImageUrl,
      }));
    }
  }, []);

  return {
    imageUrl,
    imageUrlsByCameraId,
    framesByCameraId,
    cameraSlots,
    hasRequiredReferenceFrames,
    handlePreviewFrame,
    refreshLatestImages,
  };
}
