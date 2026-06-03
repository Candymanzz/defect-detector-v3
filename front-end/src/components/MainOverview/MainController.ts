import { orchestratorApi } from "../../shared/api";
import { errorMessage } from "../../shared/lib/errors";
import type { InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import type { BackendStatus, CameraCardData, CameraImageUrlsById, MainOverviewData, SelectedCamera } from "./type";

const CAMERAS_PER_OBJECT = 5;
const CAMERA_LIMIT = 5;

export const FALLBACK_CAMERA_IDS = Array.from({ length: CAMERA_LIMIT }, (_, index) => index);
export const INITIAL_BACKEND_STATUS: BackendStatus = {
  state: "loading",
  text: "checking",
};

export async function loadMainOverviewData(): Promise<MainOverviewData> {
  const backendHealth = await loadBackendHealth();
  const backendCameraIds = await loadBackendCameraIds();

  return {
    backendStatus: {
      state: "ready",
      text: backendHealth,
    },
    cameraIds: backendCameraIds,
  };
}

export function createMainOverviewErrorData(error: unknown): MainOverviewData {
  return {
    backendStatus: {
      state: "error",
      text: errorMessage(error),
    },
    cameraIds: FALLBACK_CAMERA_IDS,
  };
}

export function createCameraCards(
  cameraIds: number[],
  backendReady: boolean,
  imageUrlsByCameraId: CameraImageUrlsById = {},
): CameraCardData[] {
  return cameraIds.map((cameraId, index) => createCameraCardData(cameraId, index, backendReady, imageUrlsByCameraId));
}

export function createSelectedCamera(camera: CameraCardData): SelectedCamera {
  return {
    cameraId: camera.cameraId,
    objectName: camera.objectName,
  };
}

export function getModalCameraImageUrl(selectedCamera: SelectedCamera | null, backendReady: boolean) {
  return selectedCamera && backendReady ? orchestratorApi.currentFrameUrl(selectedCamera.cameraId) : undefined;
}

export function createWsFrameImageUrl(frame: PreviewFramePayload | InspectResultPayload) {
  const imagePath = frame.http_path ?? frame.current.http_path;

  if (imagePath) {
    return orchestratorApi.imageUrl(imagePath, frame.frame_id);
  }

  return orchestratorApi.currentFrameUrl(frame.camera_id, frame.frame_id);
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
  const backendCameraIds = cameraIds.slice(0, CAMERA_LIMIT);
  return backendCameraIds.length ? backendCameraIds : FALLBACK_CAMERA_IDS;
}

function createCameraCardData(
  cameraId: number,
  index: number,
  backendReady: boolean,
  imageUrlsByCameraId: CameraImageUrlsById,
): CameraCardData {
  return {
    cameraId,
    objectName: getObjectName(index),
    imageUrl: backendReady ? (imageUrlsByCameraId[cameraId] ?? orchestratorApi.currentFrameUrl(cameraId)) : undefined,
  };
}

function getObjectName(index: number) {
  return index < CAMERAS_PER_OBJECT ? "Object 1" : "Object 2";
}
