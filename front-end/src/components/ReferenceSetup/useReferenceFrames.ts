import { useCallback, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { UiLatestSnapshot } from "../../shared/api/types";
import type { PreviewFramePayload } from "../../shared/ws";
import { REFERENCE_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";

export function useReferenceFrames() {
  const [liveFramesByCameraId, setLiveFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [liveImageUrlsByCameraId, setLiveImageUrlsByCameraId] = useState<Record<number, string>>({});
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
        liveFramesByCameraId[cameraId],
        liveImageUrlsByCameraId[cameraId],
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
        setFramesByCameraId,
        setImageUrlsByCameraId,
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
        liveFramesByCameraId[cameraId],
        liveImageUrlsByCameraId[cameraId],
        setFramesByCameraId,
        setImageUrlsByCameraId,
      );

      if (loaded) {
        loadedCameraIds.push(cameraId);
      } else {
        const snapshotLoaded = await loadSnapshotImage(
          cameraId,
          setSnapshotImageUrlsByCameraId,
          setFramesByCameraId,
          setImageUrlsByCameraId,
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

      if (REFERENCE_REQUIRED_CAMERA_IDS.includes(previewFrame.camera_id as (typeof REFERENCE_REQUIRED_CAMERA_IDS)[number])) {
        lockInitialReferenceFrame(
          previewFrame.camera_id,
          previewFrame,
          nextImageUrl,
          setFramesByCameraId,
          setImageUrlsByCameraId,
        );
      }
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
  setFramesByCameraId: Dispatch<SetStateAction<Record<number, PreviewFramePayload>>>,
  setImageUrlsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
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
    // Snapshot fallback is also used as a valid reference frame source.
    const snapshotFrame = snapshotToPreviewFrame(snapshot);
    setFramesByCameraId((prevFrames) => ({
      ...prevFrames,
      [cameraId]: snapshotFrame,
    }));
    setImageUrlsByCameraId((prevImageUrls) => ({
      ...prevImageUrls,
      [cameraId]: imageUrl,
    }));

    return true;
  } catch {
    return false;
  }
}

function snapshotToPreviewFrame(snapshot: UiLatestSnapshot): PreviewFramePayload {
  return {
    camera_id: snapshot.cameraId,
    frame_id: String(snapshot.frameId),
    session_state: "NO_REFERENCE",
    current: {
      camera_id: snapshot.cameraId,
      frame_id: snapshot.frameId,
      shm_name: snapshot.shmName,
      width: snapshot.capture.width,
      height: snapshot.capture.height,
      stride: snapshot.capture.width * 3,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
      http_path: snapshot.currentJpeg.path,
    },
    http_path: snapshot.currentJpeg.path,
    detector: {
      detector_id: snapshot.detectorId,
      product_type: snapshot.productType,
    },
    server_ts_ms: snapshot.updatedAtMs,
  };
}

function lockInitialReferenceFrame(
  cameraId: number,
  previewFrame: PreviewFramePayload,
  imageUrl: string,
  setFramesByCameraId: Dispatch<SetStateAction<Record<number, PreviewFramePayload>>>,
  setImageUrlsByCameraId: Dispatch<SetStateAction<Record<number, string>>>,
) {
  setFramesByCameraId((prevFrames) => {
    if (prevFrames[cameraId]) {
      return prevFrames;
    }

    return {
      ...prevFrames,
      [cameraId]: previewFrame,
    };
  });
  setImageUrlsByCameraId((prevImageUrls) => {
    if (prevImageUrls[cameraId]) {
      return prevImageUrls;
    }

    return {
      ...prevImageUrls,
      [cameraId]: imageUrl,
    };
  });
}
