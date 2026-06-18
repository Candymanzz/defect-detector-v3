import { orchestratorApi } from "../../shared/api";
import type { InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import type { BackendStatus, CameraCardData, CameraImageUrlsById, MainOverviewData, SelectedCamera } from "./type";

const CAMERAS_PER_OBJECT = 5;
const FALLBACK_OBJECT_COUNT = 2;

export const FALLBACK_CAMERA_IDS = Array.from(
  { length: CAMERAS_PER_OBJECT * FALLBACK_OBJECT_COUNT },
  (_, index) => index,
);
export const INITIAL_BACKEND_STATUS: BackendStatus = {
  state: "loading",
  text: "Проверка...",
};

export async function loadMainOverviewData(): Promise<MainOverviewData> {
  await loadBackendHealth();
  const backendCameraIds = await loadBackendCameraIds();

  return {
    backendStatus: {
      state: "ready",
      text: "Подключено",
    },
    cameraIds: backendCameraIds,
  };
}

export function createMainOverviewErrorData(): MainOverviewData {
  return {
    backendStatus: {
      state: "error",
      text: "Нет подключения",
    },
    cameraIds: FALLBACK_CAMERA_IDS,
  };
}

export function createCameraCards(
  cameraIds: number[],
  imageUrlsByCameraId: CameraImageUrlsById = {},
): CameraCardData[] {
  return cameraIds.map((cameraId, index) => createCameraCardData(cameraId, index, imageUrlsByCameraId));
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

async function loadBackendHealth() {
  const health = await orchestratorApi.health();
  return health.trim() || "ok";
}

async function loadBackendCameraIds() {
  const cameraList = await orchestratorApi.listCameras();
  return getCameraIdsOrFallback(cameraList.cameras);
}

function getCameraIdsOrFallback(cameraIds: number[]) {
  if (!cameraIds.length) {
    return FALLBACK_CAMERA_IDS;
  }

  return [...new Set(cameraIds)].sort((left, right) => left - right);
}

function createCameraCardData(
  cameraId: number,
  index: number,
  imageUrlsByCameraId: CameraImageUrlsById,
): CameraCardData {
  return {
    cameraId,
    objectName: getObjectName(index),
    imageUrl: imageUrlsByCameraId[cameraId],
  };
}

function getObjectName(index: number) {
  return `Object ${Math.floor(index / CAMERAS_PER_OBJECT) + 1}`;
}
