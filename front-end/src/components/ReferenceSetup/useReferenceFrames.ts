import { useCallback, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { PreviewFramePayload } from "../../shared/ws";
import { REFERENCE_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";

export function useReferenceFrames() {
  const [liveFramesByCameraId, setLiveFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [liveImageUrlsByCameraId, setLiveImageUrlsByCameraId] = useState<Record<number, string>>({});
  const liveFramesByCameraIdRef = useRef(liveFramesByCameraId);
  const liveImageUrlsByCameraIdRef = useRef(liveImageUrlsByCameraId);
  const [framesByCameraId, setFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<Record<number, string>>({});
  const [snapshotImageUrlsByCameraId, setSnapshotImageUrlsByCameraId] = useState<Record<number, string>>({});
  const cameraSlots = REFERENCE_CAMERA_IDS.map((cameraId) => ({
    cameraId,
    frame: framesByCameraId[cameraId],
    imageUrl: imageUrlsByCameraId[cameraId] ?? snapshotImageUrlsByCameraId[cameraId],
  }));
  const hasRequiredReferenceFrames = REFERENCE_REQUIRED_CAMERA_IDS.every((cameraId) => framesByCameraId[cameraId]);

  const refreshLatestImages = useCallback(async (cameraId?: number) => {
    if (cameraId !== undefined) {
      const loaded = commitLiveReferenceFrame(
        cameraId,
        liveFramesByCameraIdRef.current[cameraId],
        liveImageUrlsByCameraIdRef.current[cameraId],
        setFramesByCameraId,
        setImageUrlsByCameraId,
      );

      if (loaded) {
        return {
          loadedCameraIds: [cameraId],
          snapshotCameraIds: [],
          missingCameraIds: [],
        };
      }

      const snapshotLoaded = await loadSnapshotImage(
        cameraId,
        setSnapshotImageUrlsByCameraId,
      );

      return snapshotLoaded
        ? {
            loadedCameraIds: [],
            snapshotCameraIds: [cameraId],
            missingCameraIds: [],
          }
        : {
            loadedCameraIds: [],
            snapshotCameraIds: [],
            missingCameraIds: [cameraId],
          };
    }

    const loadedCameraIds: number[] = [];
    const snapshotCameraIds: number[] = [];
    const missingCameraIds: number[] = [];

    for (const cameraId of REFERENCE_CAMERA_IDS) {
      const loaded = commitLiveReferenceFrame(
        cameraId,
        liveFramesByCameraIdRef.current[cameraId],
        liveImageUrlsByCameraIdRef.current[cameraId],
        setFramesByCameraId,
        setImageUrlsByCameraId,
      );

      if (loaded) {
        loadedCameraIds.push(cameraId);
      } else {
        const snapshotLoaded = await loadSnapshotImage(
          cameraId,
          setSnapshotImageUrlsByCameraId,
        );

        if (snapshotLoaded) {
          snapshotCameraIds.push(cameraId);
        } else {
          missingCameraIds.push(cameraId);
        }
      }
    }

    return {
      loadedCameraIds,
      snapshotCameraIds,
      missingCameraIds,
    };
  }, []);

  const handlePreviewFrame = useCallback((previewFrame: PreviewFramePayload) => {
    const imagePath = previewFrame.http_path ?? previewFrame.current.http_path;
    const nextImageUrl = imagePath
      ? orchestratorApi.imageUrl(imagePath, previewFrame.frame_id)
      : orchestratorApi.currentFrameUrl(previewFrame.camera_id, previewFrame.frame_id);

    const nextLiveFrames = {
      ...liveFramesByCameraIdRef.current,
      [previewFrame.camera_id]: previewFrame,
    };
    liveFramesByCameraIdRef.current = nextLiveFrames;
    setLiveFramesByCameraId(nextLiveFrames);
    const nextLiveImageUrls = {
      ...liveImageUrlsByCameraIdRef.current,
      [previewFrame.camera_id]: nextImageUrl,
    };
    liveImageUrlsByCameraIdRef.current = nextLiveImageUrls;
    setLiveImageUrlsByCameraId(nextLiveImageUrls);

    if (REFERENCE_REQUIRED_CAMERA_IDS.includes(previewFrame.camera_id as (typeof REFERENCE_REQUIRED_CAMERA_IDS)[number])) {
      updateReferenceFrame(
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
  setFramesByCameraId: Dispatch<SetStateAction<Record<number, PreviewFramePayload>>>,
  setImageUrlsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
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

async function loadSnapshotImage(
  cameraId: number,
  setSnapshotImageUrlsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
) {
  try {
    const snapshot = await orchestratorApi.getLatestSnapshot(cameraId);

    if (!snapshot.hasCurrent || !snapshot.currentJpeg?.path) {
      return false;
    }

    const imageUrl = orchestratorApi.imageUrl(snapshot.currentJpeg.path, snapshot.frameId);

    setSnapshotImageUrlsByCameraId((prevImageUrls) => ({
      ...prevImageUrls,
      [cameraId]: imageUrl,
    }));

    return true;
  } catch {
    return false;
  }
}

function updateReferenceFrame(
  cameraId: number,
  previewFrame: PreviewFramePayload,
  imageUrl: string,
  setFramesByCameraId: Dispatch<SetStateAction<Record<number, PreviewFramePayload>>>,
  setImageUrlsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
) {
  setFramesByCameraId((prevFrames) => ({
    ...prevFrames,
    [cameraId]: previewFrame,
  }));
  setImageUrlsByCameraId((prevImageUrls) => ({
    ...prevImageUrls,
    [cameraId]: imageUrl,
  }));
}
