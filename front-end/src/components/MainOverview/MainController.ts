import { orchestratorApi } from "../../shared/api";
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
export const CAMERA_FRAME_STALE_MS = 15000;

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
