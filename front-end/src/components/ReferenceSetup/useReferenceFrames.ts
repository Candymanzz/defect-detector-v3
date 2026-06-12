import { useCallback, useRef, useState } from "react";
import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import { orchestratorApi } from "../../shared/api/orchestratorApi";
import type { UiLatestSnapshot } from "../../shared/api/types";
import type { PreviewFramePayload } from "../../shared/ws";

export function useReferenceFrames(cameraIds: number[]) {
  const liveFramesByCameraIdRef = useRef<Record<number, PreviewFramePayload>>({});
  const liveImageUrlsByCameraIdRef = useRef<Record<number, string>>({});
  const lockedCameraIdsRef = useRef<Record<number, boolean>>({});
  const pendingCameraIdsRef = useRef<Record<number, boolean>>({});
  const [framesByCameraId, setFramesByCameraId] = useState<Record<number, PreviewFramePayload>>({});
  const [imageUrlsByCameraId, setImageUrlsByCameraId] = useState<Record<number, string>>({});
  const [snapshotImageUrlsByCameraId, setSnapshotImageUrlsByCameraId] = useState<Record<number, string>>({});
  const cameraSlots = cameraIds.map((cameraId) => ({
    cameraId,
    frame: framesByCameraId[cameraId],
    imageUrl: imageUrlsByCameraId[cameraId] ?? snapshotImageUrlsByCameraId[cameraId],
  }));
  const hasRequiredReferenceFrames =
    cameraIds.length > 0 && cameraIds.every((cameraId) => framesByCameraId[cameraId]);

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
        setFramesByCameraId,
        setImageUrlsByCameraId,
        lockedCameraIdsRef,
        pendingCameraIdsRef,
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

    for (const cameraId of cameraIds) {
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
          setFramesByCameraId,
          setImageUrlsByCameraId,
          lockedCameraIdsRef,
          pendingCameraIdsRef,
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
  }, [cameraIds]);

  const handlePreviewFrame = useCallback((previewFrame: PreviewFramePayload) => {
    const imagePath = previewFrame.http_path ?? previewFrame.current.http_path;
    const nextImageUrl = imagePath ? orchestratorApi.imageUrl(imagePath, previewFrame.frame_id) : undefined;

    liveFramesByCameraIdRef.current = {
      ...liveFramesByCameraIdRef.current,
      [previewFrame.camera_id]: previewFrame,
    };
    const cameraId = previewFrame.camera_id;
    const isRequiredCamera = cameraIds.includes(cameraId);

    if (
      nextImageUrl &&
      isRequiredCamera &&
      !lockedCameraIdsRef.current[cameraId] &&
      !pendingCameraIdsRef.current[cameraId]
    ) {
      pendingCameraIdsRef.current[cameraId] = true;

      void freezeImageUrl(nextImageUrl)
        .then((frozenImageUrl) => {
          if (lockedCameraIdsRef.current[cameraId]) {
            URL.revokeObjectURL(frozenImageUrl);
            return;
          }

          liveImageUrlsByCameraIdRef.current = {
            ...liveImageUrlsByCameraIdRef.current,
            [cameraId]: frozenImageUrl,
          };
          lockInitialReferenceFrame(
            cameraId,
            previewFrame,
            frozenImageUrl,
            setFramesByCameraId,
            setImageUrlsByCameraId,
          );
          lockedCameraIdsRef.current[cameraId] = true;
        })
        .catch(() => {
          // A snapshot fallback can still provide a reference frame.
        })
        .finally(() => {
          delete pendingCameraIdsRef.current[cameraId];
        });
    }
  }, [cameraIds]);

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
  if (
    !liveFrame ||
    !liveImageUrl ||
    liveFrame.camera_id !== cameraId ||
    liveFrame.current.camera_id !== cameraId
  ) {
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
  lockedCameraIdsRef: MutableRefObject<Record<number, boolean>>,
  pendingCameraIdsRef: MutableRefObject<Record<number, boolean>>,
) {
  if (lockedCameraIdsRef.current[cameraId] || pendingCameraIdsRef.current[cameraId]) {
    return false;
  }

  pendingCameraIdsRef.current[cameraId] = true;

  try {
    const snapshot = await orchestratorApi.getLatestSnapshot(cameraId);

    if (!snapshot.hasCurrent || !snapshot.currentJpeg?.path || snapshot.cameraId !== cameraId) {
      return false;
    }

    const sourceImageUrl = orchestratorApi.imageUrl(snapshot.currentJpeg.path, snapshot.frameId);
    const imageUrl = await freezeImageUrl(sourceImageUrl);

    if (lockedCameraIdsRef.current[cameraId]) {
      URL.revokeObjectURL(imageUrl);
      return false;
    }

    lockedCameraIdsRef.current[cameraId] = true;
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
  } finally {
    delete pendingCameraIdsRef.current[cameraId];
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

async function freezeImageUrl(imageUrl: string) {
  const response = await fetch(imageUrl, {
    cache: "no-store",
  });

  if (!response.ok) {
    throw new Error(`Failed to freeze reference image: HTTP ${response.status}`);
  }

  return URL.createObjectURL(await response.blob());
}
