import { useCallback, useState } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { PreviewFramePayload } from "../../shared/ws";
import { REFERENCE_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";

export function useReferenceFrames() {
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
      const loaded = commitLiveReferenceFrame(
        cameraId,
        liveFramesByCameraId[cameraId],
        liveImageUrlsByCameraId[cameraId],
        setFramesByCameraId,
        setImageUrlsByCameraId,
      );

      return loaded
        ? {
            loadedCameraIds: [cameraId],
            missingCameraIds: [],
          }
        : {
            loadedCameraIds: [],
            missingCameraIds: [cameraId],
          };
    }

    const loadedCameraIds: number[] = [];
    const missingCameraIds: number[] = [];

    for (const cameraId of REFERENCE_CAMERA_IDS) {
      const loaded = commitLiveReferenceFrame(
        cameraId,
        liveFramesByCameraId[cameraId],
        liveImageUrlsByCameraId[cameraId],
        setFramesByCameraId,
        setImageUrlsByCameraId,
      );

      if (loaded) {
        loadedCameraIds.push(cameraId);
      } else {
        missingCameraIds.push(cameraId);
      }
    }

    return {
      loadedCameraIds,
      missingCameraIds,
    };
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

    if (nextImageUrl && REFERENCE_REQUIRED_CAMERA_IDS.includes(previewFrame.camera_id as (typeof REFERENCE_REQUIRED_CAMERA_IDS)[number])) {
      commitLiveReferenceFrame(
        previewFrame.camera_id,
        previewFrame,
        nextImageUrl,
        setFramesByCameraId,
        setImageUrlsByCameraId,
      );
    }
  }, []);

  return {
    imageUrlsByCameraId,
    framesByCameraId,
    cameraSlots,
    hasRequiredReferenceFrames,
    handlePreviewFrame,
    refreshLatestImages,
  };
}

function commitLiveReferenceFrame(
  cameraId: number,
  liveFrame: PreviewFramePayload | undefined,
  liveImageUrl: string | undefined,
  setFramesByCameraId: React.Dispatch<React.SetStateAction<Record<number, PreviewFramePayload>>>,
  setImageUrlsByCameraId: React.Dispatch<React.SetStateAction<Record<number, string>>>,
) {
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

  return true;
}
