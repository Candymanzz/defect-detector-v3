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

  const handlePreviewFrame = useCallback((previewFrame: PreviewFramePayload) => {
    const imagePath = previewFrame.http_path ?? previewFrame.current.http_path;
    const nextImageUrl = imagePath
      ? orchestratorApi.imageUrl(imagePath, previewFrame.frame_id)
      : orchestratorApi.currentFrameUrl(previewFrame.camera_id, previewFrame.frame_id);

    setFramesByCameraId((prevFrames) => ({
      ...prevFrames,
      [previewFrame.camera_id]: previewFrame,
    }));
    setImageUrlsByCameraId((prevImageUrls) => ({
      ...prevImageUrls,
      [previewFrame.camera_id]: nextImageUrl,
    }));
    setImageUrl(nextImageUrl);
  }, []);

  return {
    imageUrl,
    framesByCameraId,
    cameraSlots,
    hasRequiredReferenceFrames,
    handlePreviewFrame,
  };
}
