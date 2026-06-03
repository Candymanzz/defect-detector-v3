import { useCallback, useState } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { PreviewFramePayload } from "../../shared/ws";
import { REFERENCE_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";

export function useReferenceFrames() {
  const [imageUrl, setImageUrl] = useState<string>();
  const [framesByCameraId, setFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<Record<number, string>>({});
  const cameraSlots = REFERENCE_CAMERA_IDS.map((cameraId) => ({
    cameraId,
    frame: framesByCameraId[cameraId],
    imageUrl: imageUrlsByCameraId[cameraId],
  }));
  const hasRequiredReferenceFrames = REFERENCE_REQUIRED_CAMERA_IDS.every((cameraId) => framesByCameraId[cameraId]);

  const loadLatestImage = useCallback(async (cameraId: number) => {
    try {
      const snapshot = await orchestratorApi.getLatestSnapshot(cameraId);

      if (!snapshot.hasCurrent) {
        return;
      }

      const nextImageUrl = orchestratorApi.imageUrl(snapshot.currentJpeg.path, snapshot.frameId);

      setImageUrlsByCameraId((prevImageUrls) => ({
        ...prevImageUrls,
        [cameraId]: nextImageUrl,
      }));
      setImageUrl(nextImageUrl);
    } catch {
      // The modal can still receive an image from the next preview_frame.
    }
  }, []);

  const refreshLatestImages = useCallback(async () => {
    await Promise.all(REFERENCE_CAMERA_IDS.map((cameraId) => loadLatestImage(cameraId)));
  }, [loadLatestImage]);

  const handlePreviewFrame = useCallback((previewFrame: PreviewFramePayload) => {
    const imagePath = previewFrame.http_path ?? previewFrame.current.http_path;
    const nextImageUrl = imagePath ? orchestratorApi.imageUrl(imagePath, previewFrame.frame_id) : undefined;

    setFramesByCameraId((prevFrames) => ({
      ...prevFrames,
      [previewFrame.camera_id]: previewFrame,
    }));
    if (nextImageUrl) {
      setImageUrlsByCameraId((prevImageUrls) => ({
        ...prevImageUrls,
        [previewFrame.camera_id]: nextImageUrl,
      }));
      setImageUrl(nextImageUrl);
    } else {
      void loadLatestImage(previewFrame.camera_id);
    }
  }, [loadLatestImage]);

  return {
    imageUrl,
    framesByCameraId,
    cameraSlots,
    hasRequiredReferenceFrames,
    handlePreviewFrame,
    refreshLatestImages,
  };
}
