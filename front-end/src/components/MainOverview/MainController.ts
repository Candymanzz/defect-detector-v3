import { orchestratorApi } from "../../shared/api";
import type { UiLatestSnapshot } from "../../shared/api/types";
import { resolveInspectionResultState } from "../../shared/inspectResult";
import { compareFrameIds } from "../../shared/lib/frameIds";
import { getReferenceImage } from "../../shared/referenceImages";
import type { HeatmapDescriptor, InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import type {
  BackendStatus,
  CameraCardData,
  CameraImageUrlsById,
  InspectionControlState,
  InspectionHistoryItem,
  MainOverviewData,
  ModalInspectionSnapshot,
  SelectedCamera,
} from "./type";

const CAMERAS_PER_OBJECT = 5;
const FALLBACK_OBJECT_COUNT = 2;
export const INSPECTION_HISTORY_LIMIT = 20;

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

export function createInspectionControlStates(inspectionStatus: {
  enabledCameraIds: number[];
  disabledCameraIds: number[];
}) {
  const states: Record<number, InspectionControlState> = {};
  for (const cameraId of inspectionStatus.enabledCameraIds) {
    states[cameraId] = {
      isEnabled: true,
      state: "idle",
      message: "Inspection enabled",
    };
  }
  for (const cameraId of inspectionStatus.disabledCameraIds) {
    states[cameraId] = {
      isEnabled: false,
      state: "idle",
      message: "Inspection stopped",
    };
  }
  return states;
}

export function resolveCardInspectImageUrl(
  inspectResult: InspectResultPayload | undefined,
  artifactInspectResult: InspectResultPayload | undefined,
) {
  if (
    !inspectResult ||
    !artifactInspectResult?.artifact_bundle_id ||
    artifactInspectResult.frame_id !== inspectResult.frame_id
  ) {
    return undefined;
  }

  return orchestratorApi.url(
    `/api/inspection-artifacts/${encodeURIComponent(artifactInspectResult.artifact_bundle_id)}/card.jpg`,
  );
}

export function createModalInspectionSnapshot(
  camera: SelectedCamera,
  inspectResult: InspectResultPayload | undefined,
  artifactInspectResult: InspectResultPayload | undefined,
  previewFrameId: string | undefined,
  previewImageUrl: string | undefined,
  inspectionHistory: InspectionHistoryItem[],
): ModalInspectionSnapshot {
  const snapshotResult =
    inspectResult && artifactInspectResult?.frame_id === inspectResult.frame_id
      ? artifactInspectResult
      : (inspectResult ?? artifactInspectResult);
  const inspectImageUrl = snapshotResult
    ? (resolveImmutableInspectionImageUrl(snapshotResult) ?? createWsFrameImageUrl(snapshotResult))
    : undefined;
  const matchingPreviewImageUrl =
    snapshotResult && previewFrameId === snapshotResult.frame_id ? previewImageUrl : undefined;
  const referenceImage = getReferenceImage(camera.cameraId);

  return {
    ...camera,
    initialFrameId: snapshotResult?.frame_id,
    inspectResult: snapshotResult,
    cameraImageUrl: inspectImageUrl ?? matchingPreviewImageUrl,
    heatmapUrl: snapshotResult?.heatmap ? resolveHeatmapSourceUrlOrUndefined(snapshotResult.heatmap) : undefined,
    referenceImageUrl: referenceImage?.imageUrl,
    referenceRoiPoints: referenceImage?.roiPoints.map((point) => ({ ...point })),
    inspectionItems: createInitialModalInspectionItems(inspectionHistory, snapshotResult),
  };
}

export function selectModalInspection(currentSnapshot: ModalInspectionSnapshot | null, frameId: string) {
  if (!currentSnapshot || currentSnapshot.inspectResult?.frame_id === frameId) {
    return currentSnapshot;
  }

  const item = currentSnapshot.inspectionItems.find((candidate) => candidate.frameId === frameId);
  return item ? updateModalSnapshotResult(currentSnapshot, item.inspectResult) : currentSnapshot;
}

export function updateModalSnapshotResult(
  currentSnapshot: ModalInspectionSnapshot,
  inspectResult: InspectResultPayload,
) {
  return {
    ...currentSnapshot,
    inspectResult,
    cameraImageUrl: resolveImmutableInspectionImageUrl(inspectResult) ?? createWsFrameImageUrl(inspectResult),
    heatmapUrl: inspectResult.heatmap ? resolveHeatmapSourceUrlOrUndefined(inspectResult.heatmap) : undefined,
  };
}

export function compareInspectResults(left: InspectResultPayload, right: InspectResultPayload) {
  const frameOrder = compareFrameIds(left.frame_id, right.frame_id);
  return frameOrder !== 0 ? frameOrder : Math.sign(left.server_ts_ms - right.server_ts_ms);
}

export function isInspectionCounterReset(previous: InspectResultPayload, candidate: InspectResultPayload) {
  return candidate.server_ts_ms >= previous.server_ts_ms && compareFrameIds(candidate.frame_id, previous.frame_id) < 0;
}

export function hasDisplayableInspectImage(inspectResult: InspectResultPayload) {
  return Boolean(inspectResult.artifact_bundle_id || inspectResult.http_path || inspectResult.current.http_path);
}

export function hasImmutableInspectArtifact(inspectResult: InspectResultPayload) {
  return Boolean(inspectResult.artifact_bundle_id);
}

export function upsertInspectionHistoryItem(items: InspectionHistoryItem[], nextItem: InspectionHistoryItem) {
  return [nextItem, ...items.filter((item) => item.frameId !== nextItem.frameId)].sort((left, right) =>
    compareFrameIds(right.frameId, left.frameId),
  );
}

export function resolveInspectionId(inspectResult: InspectResultPayload) {
  return inspectResult.inspection_id ?? inspectResult.frame_id;
}

export function upsertModalInspectionItem(items: InspectionHistoryItem[], nextItem: InspectionHistoryItem) {
  return [...items.filter((item) => item.frameId !== nextItem.frameId), nextItem]
    .sort((left, right) => compareFrameIds(left.frameId, right.frameId))
    .slice(-INSPECTION_HISTORY_LIMIT);
}

export function latestSnapshotToInspectResult(snapshot: UiLatestSnapshot): InspectResultPayload | undefined {
  const hasDecision =
    snapshot.python_status != null ||
    snapshot.geometry_status != null ||
    snapshot.overall_pass != null ||
    snapshot.action != null;
  if (!hasDecision || snapshot.frameId < 0) {
    return undefined;
  }

  return {
    camera_id: snapshot.cameraId,
    frame_id: String(snapshot.frameId),
    session_state: "READY",
    current: {
      camera_id: snapshot.cameraId,
      frame_id: String(snapshot.frameId),
      shm_name: snapshot.shmName ?? "",
      width: snapshot.capture.width,
      height: snapshot.capture.height,
      stride: 0,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
      http_path: snapshot.currentJpeg?.path,
    },
    http_path: snapshot.currentJpeg?.path,
    artifact_bundle_id: undefined,
    heatmap:
      snapshot.hasHeatmap && snapshot.heatmapU8?.path
        ? {
            width: snapshot.heatmapU8.width,
            height: snapshot.heatmapU8.height,
            pixel_format: "gray_u8",
            channels: 1,
            http_path: snapshot.heatmapU8.path,
          }
        : null,
    active_reference_view_index: 0,
    detector: {
      detector_id: snapshot.detectorId,
      product_type: snapshot.productType,
    },
    overall_pass: snapshot.overall_pass ?? undefined,
    action: snapshot.action ?? undefined,
    anomaly_score: snapshot.anomaly_score ?? undefined,
    python_status: snapshot.python_status ?? undefined,
    geometry_status: snapshot.geometry_status ?? undefined,
    fp_zones: [],
    server_ts_ms: snapshot.updatedAtMs,
  };
}

function resolveImmutableInspectionImageUrl(inspectResult: InspectResultPayload) {
  if (!inspectResult.artifact_bundle_id) {
    return undefined;
  }

  return orchestratorApi.url(
    `/api/inspection-artifacts/${encodeURIComponent(inspectResult.artifact_bundle_id)}/frame.jpg`,
  );
}

function createInitialModalInspectionItems(
  inspectionHistory: InspectionHistoryItem[],
  selectedResult: InspectResultPayload | undefined,
) {
  if (!selectedResult) {
    return [];
  }

  const selectedState = resolveInspectionResultState(selectedResult);
  const availableItems =
    selectedState === undefined
      ? inspectionHistory
      : upsertInspectionHistoryItem(inspectionHistory, {
          frameId: selectedResult.frame_id,
          inspectionId: resolveInspectionId(selectedResult),
          result: selectedState,
          inspectResult: selectedResult,
        });

  return availableItems
    .filter((item) => compareFrameIds(item.frameId, selectedResult.frame_id) <= 0)
    .slice(0, INSPECTION_HISTORY_LIMIT)
    .reverse();
}

function resolveHeatmapSourceUrlOrUndefined(heatmap: HeatmapDescriptor) {
  if (heatmap.http_path) {
    return orchestratorApi.url(heatmap.http_path);
  }
  if (heatmap.artifact_id) {
    return orchestratorApi.heatmapArtifactUrl(heatmap.artifact_id);
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
