import { orchestratorApi } from "../../shared/api";
import type { UiLatestSnapshot } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import type { InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import type {
  BackendStatus,
  CameraCardData,
  CameraFrameTimesById,
  CameraImageUrlsById,
  SelectedCamera,
} from "./type";

const CAMERAS_PER_OBJECT = 5;
const CAMERA_LIMIT = 5;
export const CAMERA_FRAME_STALE_MS = 30000;

export const FALLBACK_CAMERA_IDS = Array.from({ length: CAMERA_LIMIT }, (_, index) => index);
export const INITIAL_BACKEND_STATUS: BackendStatus = {
  state: "loading",
  text: "checking",
};

export async function loadBackendStatus(): Promise<BackendStatus> {
  const health = await orchestratorApi.health();
  return {
    state: "ready",
    text: health.trim() || "ok",
  };
}

export function createBackendErrorStatus(error: unknown): BackendStatus {
  return {
    state: "error",
    text: errorMessage(error),
  };
}

export async function loadBackendCameraIds() {
  const cameraList = await orchestratorApi.listCameras();
  return getCameraIdsOrFallback(cameraList.cameras);
}

export async function loadBackendCameraSnapshots(cameraIds: number[]) {
  const results = await Promise.allSettled(cameraIds.map((cameraId) => orchestratorApi.getLatestSnapshot(cameraId)));
  return results.flatMap((result) => (result.status === "fulfilled" ? [result.value] : []));
}

export function createSnapshotImageUrl(snapshot: UiLatestSnapshot) {
  if (!snapshot.hasCurrent || snapshot.frameId < 0 || !snapshot.currentJpeg?.path) {
    return undefined;
  }

  return orchestratorApi.imageUrl(snapshot.currentJpeg.path, snapshot.frameId);
}

export function createCameraCards(
  cameraIds: number[],
  imageUrlsByCameraId: CameraImageUrlsById = {},
  frameTimesByCameraId: CameraFrameTimesById = {},
  nowMs = Date.now(),
  monitoringStartedAtMs = nowMs,
): CameraCardData[] {
  return cameraIds.map((cameraId, index) =>
    createCameraCardData(cameraId, index, imageUrlsByCameraId, frameTimesByCameraId, nowMs, monitoringStartedAtMs),
  );
}

export function createSelectedCamera(camera: CameraCardData): SelectedCamera {
  return {
    cameraId: camera.cameraId,
    objectName: camera.objectName,
  };
}

export function createWsFrameImageUrl(frame: PreviewFramePayload | InspectResultPayload) {
  const imagePath = frame.http_path ?? frame.current.http_path;

  if (imagePath) {
    return orchestratorApi.imageUrl(imagePath, frame.frame_id);
  }

  return undefined;
}

export function isIncomingFrameNewer(
  incoming: PreviewFramePayload | InspectResultPayload,
  current: PreviewFramePayload | InspectResultPayload | undefined,
) {
  if (!current) {
    return true;
  }

  const incomingFrameId = parseFrameId(incoming.frame_id);
  const currentFrameId = parseFrameId(current.frame_id);

  if (incomingFrameId !== null && currentFrameId !== null) {
    if (incomingFrameId !== currentFrameId) {
      return incomingFrameId > currentFrameId;
    }
  } else if (incoming.frame_id !== current.frame_id) {
    return incoming.server_ts_ms > current.server_ts_ms;
  }

  return incoming.server_ts_ms >= current.server_ts_ms;
}

export function isFrameSequenceReset(
  incoming: PreviewFramePayload,
  current: PreviewFramePayload | undefined,
) {
  if (!current || incoming.server_ts_ms <= current.server_ts_ms) {
    return false;
  }

  const incomingFrameId = parseFrameId(incoming.frame_id);
  const currentFrameId = parseFrameId(current.frame_id);
  return incomingFrameId !== null && currentFrameId !== null && incomingFrameId < currentFrameId;
}

export function readNumericFrameId(frame: PreviewFramePayload | InspectResultPayload | undefined) {
  return frame ? parseFrameId(frame.frame_id) : null;
}

export function isFramePayloadConsistent(frame: PreviewFramePayload | InspectResultPayload) {
  return frame.current.camera_id === frame.camera_id && String(frame.current.frame_id) === frame.frame_id;
}

function parseFrameId(frameId: string) {
  return /^\d+$/.test(frameId) ? BigInt(frameId) : null;
}

function getCameraIdsOrFallback(cameraIds: number[]) {
  const backendCameraIds = cameraIds.slice(0, CAMERA_LIMIT);
  return backendCameraIds.length ? backendCameraIds : FALLBACK_CAMERA_IDS;
}

function createCameraCardData(
  cameraId: number,
  index: number,
  imageUrlsByCameraId: CameraImageUrlsById,
  frameTimesByCameraId: CameraFrameTimesById,
  nowMs: number,
  monitoringStartedAtMs: number,
): CameraCardData {
  const lastFrameAtMs = frameTimesByCameraId[cameraId];
  const signalState =
    lastFrameAtMs === undefined
      ? nowMs - monitoringStartedAtMs <= CAMERA_FRAME_STALE_MS
        ? "waiting"
        : "offline"
      : nowMs - lastFrameAtMs <= CAMERA_FRAME_STALE_MS
        ? "online"
        : "offline";

  return {
    cameraId,
    objectName: getObjectName(index),
    imageUrl: signalState === "online" ? imageUrlsByCameraId[cameraId] : undefined,
    signalState,
  };
}

function getObjectName(index: number) {
  return index < CAMERAS_PER_OBJECT ? "Object 1" : "Object 2";
}
