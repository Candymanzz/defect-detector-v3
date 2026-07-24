import { orchestratorApi } from "../../shared/api";
import type { FrameArchiveHistoryFrame, UiLatestSnapshot } from "../../shared/api/types";
import { resolveInspectionResultState } from "../../shared/inspectResult";
import { compareFrameIds } from "../../shared/lib/frameIds";
import { getReferenceImage } from "../../shared/referenceImages";
import type { HeatmapDescriptor, InspectResultPayload, PreviewFramePayload } from "../../shared/ws";
import type {
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
export const DEFAULT_INSPECTION_HISTORY_LIMIT = 20;
export let inspectionHistoryLimit = DEFAULT_INSPECTION_HISTORY_LIMIT;

export function setInspectionHistoryLimit(limit: number) {
  inspectionHistoryLimit = Math.max(0, Math.round(limit));
}

export const INSPECTION_HISTORY_LIMIT = DEFAULT_INSPECTION_HISTORY_LIMIT;

export const FALLBACK_CAMERA_IDS = Array.from(
  { length: CAMERAS_PER_OBJECT * FALLBACK_OBJECT_COUNT },
  (_, index) => index,
);
export async function loadMainOverviewData(): Promise<MainOverviewData> {
  const backendCameraIds = await loadBackendCameraIds();

  return {
    cameraIds: backendCameraIds,
  };
}

export function createMainOverviewErrorData(): MainOverviewData {
  return {
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
      message: "Инспекция включена",
    };
  }
  for (const cameraId of inspectionStatus.disabledCameraIds) {
    states[cameraId] = {
      isEnabled: false,
      state: "idle",
      message: "Инспекция остановлена",
    };
  }
  return states;
}

export function resolveCardInspectImageUrl(
  inspectResult: InspectResultPayload | undefined,
  artifactInspectResult: InspectResultPayload | undefined,
  previewFrameId?: string,
  previewImageUrl?: string,
) {
  if (inspectResult && hasDisplayableInspectImage(inspectResult)) {
    const archiveUrl = resolveArchiveFrameImageUrl(inspectResult);
    if (archiveUrl) {
      return archiveUrl;
    }
    if (
      artifactInspectResult?.artifact_bundle_id &&
      artifactInspectResult.frame_id === inspectResult.frame_id
    ) {
      return orchestratorApi.url(
        `/api/inspection-artifacts/${encodeURIComponent(artifactInspectResult.artifact_bundle_id)}/card.jpg`,
      );
    }
    return createWsFrameImageUrl(inspectResult);
  }

  if (previewFrameId && previewImageUrl) {
    if (!inspectResult || compareFrameIds(previewFrameId, inspectResult.frame_id) > 0) {
      return previewImageUrl;
    }
  }

  if (!inspectResult) {
    return previewImageUrl;
  }

  const archiveUrl = resolveArchiveFrameImageUrl(inspectResult);
  if (archiveUrl) {
    return archiveUrl;
  }

  if (
    artifactInspectResult?.artifact_bundle_id &&
    artifactInspectResult.frame_id === inspectResult.frame_id
  ) {
    return orchestratorApi.url(
      `/api/inspection-artifacts/${encodeURIComponent(artifactInspectResult.artifact_bundle_id)}/card.jpg`,
    );
  }

  return createWsFrameImageUrl(inspectResult);
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
    heatmapUrl: snapshotResult ? resolveInspectHeatmapUrl(snapshotResult) : undefined,
    referenceImageUrl: referenceImage?.imageUrl,
    referenceRoiPoints: referenceImage?.roiPoints.map((point) => ({ ...point })),
    referenceJointRoiPoints: referenceImage?.jointRoiPoints?.map((point) => ({ ...point })),
    referenceFpZones: referenceImage?.fpZones?.map((zone) => ({
      ...zone,
      points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ ...point })),
    })),
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
    heatmapUrl: resolveInspectHeatmapUrl(inspectResult),
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
  const imagePath = inspectResult.http_path ?? inspectResult.current?.http_path ?? "";
  return Boolean(inspectResult.artifact_bundle_id || imagePath.includes("/api/frame-archive/"));
}

export function upsertInspectionHistoryItem(items: InspectionHistoryItem[], nextItem: InspectionHistoryItem) {
  return trimInspectionHistoryItems([nextItem, ...items.filter((item) => item.frameId !== nextItem.frameId)]);
}

export function resolveInspectionId(inspectResult: InspectResultPayload) {
  return inspectResult.inspection_id ?? inspectResult.frame_id;
}

export function upsertModalInspectionItem(items: InspectionHistoryItem[], nextItem: InspectionHistoryItem) {
  return trimInspectionHistoryItems([...items.filter((item) => item.frameId !== nextItem.frameId), nextItem]).sort(
    (left, right) => right.inspectResult.server_ts_ms - left.inspectResult.server_ts_ms,
  );
}

/** Keep newest by wall-clock time so post-restart low frame ids are not dropped by archived high ids. */
export function trimInspectionHistoryItems(items: InspectionHistoryItem[]) {
  return [...items]
    .sort((left, right) => {
      const byTime = right.inspectResult.server_ts_ms - left.inspectResult.server_ts_ms;
      return byTime !== 0 ? byTime : compareFrameIds(right.frameId, left.frameId);
    })
    .slice(0, inspectionHistoryLimit);
}

export type ArchivedInspectionHistoryLoadResult = {
  historyByCameraId: Record<number, InspectionHistoryItem[]>;
  failedCameraIds: number[];
};

export async function loadArchivedInspectionHistory(
  cameraIds: number[],
): Promise<ArchivedInspectionHistoryLoadResult> {
  const histories = await Promise.all(
    cameraIds.map(async (cameraId) => {
      try {
        const response = await orchestratorApi.getFrameArchiveHistory(cameraId);
        setInspectionHistoryLimit(response.max_frames_per_camera);
        const frames = await Promise.all(response.frames.map((frame) => enrichArchivedFrameHeatmapSize(frame)));
        return {
          cameraId,
          items: frames.map((frame) => archivedFrameToHistoryItem(cameraId, frame)),
        };
      } catch (error) {
        return { cameraId, items: [] as InspectionHistoryItem[], error };
      }
    }),
  );

  const failedCameraIds = histories.filter((entry) => "error" in entry).map((entry) => entry.cameraId);
  if (failedCameraIds.length === cameraIds.length && cameraIds.length > 0) {
    throw new Error(`Не удалось загрузить архив камер: ${failedCameraIds.join(", ")}`);
  }

  return {
    historyByCameraId: Object.fromEntries(histories.map(({ cameraId, items }) => [cameraId, items])),
    failedCameraIds,
  };
}

async function enrichArchivedFrameHeatmapSize(frame: FrameArchiveHistoryFrame): Promise<FrameArchiveHistoryFrame> {
  if (!frame.has_heatmap || ((frame.heatmap_width ?? 0) > 0 && (frame.heatmap_height ?? 0) > 0)) {
    return frame;
  }
  if (!frame.result_url) {
    return frame;
  }

  try {
    const result = await orchestratorApi.getJson<{
      heatmap?: { width?: number; height?: number };
    }>(frame.result_url);
    const width = Number(result.heatmap?.width);
    const height = Number(result.heatmap?.height);
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
      return frame;
    }
    return {
      ...frame,
      heatmap_width: Math.round(width),
      heatmap_height: Math.round(height),
    };
  } catch {
    return frame;
  }
}

export function archivedFrameToInspectResult(
  cameraId: number,
  frame: FrameArchiveHistoryFrame,
): InspectResultPayload {
  const frameHttpPath = frame.frame_url;
  return {
    camera_id: cameraId,
    frame_id: frame.frame_id,
    inspection_id: frame.inspection_id,
    session_state: "READY",
    current: {
      camera_id: cameraId,
      frame_id: frame.frame_id,
      shm_name: "",
      width: 0,
      height: 0,
      stride: 0,
      shm_offset: 0,
      pixel_format: "bgr_u8",
      channels: 3,
      http_path: frameHttpPath,
    },
    http_path: frameHttpPath,
    heatmap:
      frame.has_heatmap && (frame.heatmap_width ?? 0) > 0 && (frame.heatmap_height ?? 0) > 0
        ? {
            width: frame.heatmap_width!,
            height: frame.heatmap_height!,
            pixel_format: "gray_u8",
            channels: 1,
            http_path: frame.heatmap_url,
          }
        : null,
    active_reference_view_index: 0,
    detector: {
      detector_id: frame.detector_id,
      product_type: frame.product_type,
    },
    overall_pass: frame.overall_pass,
    action: frame.action,
    anomaly_score: frame.anomaly_score,
    python_status: frame.python_status,
    geometry_status: frame.geometry_status,
    fp_zones: [],
    server_ts_ms: frame.saved_at_ms,
  };
}

function archivedFrameToHistoryItem(cameraId: number, frame: FrameArchiveHistoryFrame): InspectionHistoryItem {
  const inspectResult = archivedFrameToInspectResult(cameraId, frame);
  return {
    frameId: frame.frame_id,
    inspectionId: frame.inspection_id,
    result: frame.overall_pass ? "pass" : "fail",
    inspectResult,
  };
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
  const archiveUrl = resolveArchiveFrameImageUrl(inspectResult);
  if (archiveUrl) {
    return archiveUrl;
  }

  if (!inspectResult.artifact_bundle_id) {
    return undefined;
  }

  return orchestratorApi.url(
    `/api/inspection-artifacts/${encodeURIComponent(inspectResult.artifact_bundle_id)}/frame.jpg`,
  );
}

function resolveArchiveFrameImageUrl(inspectResult: InspectResultPayload) {
  const imagePath = inspectResult.http_path ?? inspectResult.current?.http_path ?? "";
  if (!imagePath.includes("/api/frame-archive/")) {
    return undefined;
  }
  return orchestratorApi.imageUrl(imagePath, inspectResult.frame_id);
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
    .slice(0, inspectionHistoryLimit);
}

function resolveInspectHeatmapUrl(inspectResult: InspectResultPayload) {
  if (!inspectResult.heatmap) {
    return undefined;
  }
  const framePath = inspectResult.http_path ?? inspectResult.current?.http_path;
  if (framePath?.includes("/api/frame-archive/") && framePath.endsWith("/frame.jpg")) {
    return orchestratorApi.url(framePath.replace(/\/frame\.jpg$/, "/heatmap.u8"));
  }
  return resolveHeatmapSourceUrlOrUndefined(inspectResult.heatmap);
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
  return `Объект ${Math.floor(index / CAMERAS_PER_OBJECT) + 1}`;
}
