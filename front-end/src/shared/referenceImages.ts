import { HttpError, orchestratorApi } from "./api";
import { isValidJointRoiPolygon } from "../components/ReferenceSetup/referenceRoi";
import type {
  ClientReferenceBundlePayload,
  FpZoneNorm,
  InterestPointNorm,
  ShmFrameRefData,
} from "./ws/types";

type ReferenceImageListener = () => void;
export type StoredReferenceImage = {
  imageUrl: string;
  frame: ShmFrameRefData;
  productType: string;
  committedAtMs?: number;
  roiPoints: InterestPointNorm[];
  jointRoiPoints?: InterestPointNorm[];
  fpZones?: FpZoneNorm[];
};
export type ArchivedReferenceGroup = {
  id: string;
  createdAtMs: number;
  cameraIds: number[];
  jointCameraId: number;
  bundle: ClientReferenceBundlePayload;
  imageUrlsByCameraId: Record<number, string>;
  images: Array<StoredReferenceImage & { cameraId: number }>;
  learnedCaseIdsByCameraId: Record<number, string[]>;
};

const referenceImagesByCameraId = new Map<number, StoredReferenceImage>();
const archivedReferenceGroups: ArchivedReferenceGroup[] = [];
let archivedReferenceGroupsVersion = 0;
let archivedReferenceGroupsSnapshotVersion = -1;
let archivedReferenceGroupsSnapshot: ArchivedReferenceGroup[] = [];
const pendingReferenceBundles = new Map<
  string,
  {
    bundle: ClientReferenceBundlePayload;
    fallbackImageUrlsByCameraId: Record<number, string>;
    roiPointsByCameraId?: Record<number, InterestPointNorm[]>;
    jointCameraId?: number;
    jointRoiPoints?: InterestPointNorm[];
  }
>();
const listeners = new Set<ReferenceImageListener>();
let referenceImageVersion = 0;
const MAX_ARCHIVED_REFERENCE_GROUPS = 24;
const REFERENCE_DB_NAME = "defect-detector-reference-library";
const REFERENCE_DB_STORE = "reference-state";
const REFERENCE_DB_KEY = "current";
let persistenceChain: Promise<void> = Promise.resolve();

type PersistedReferenceImage = Omit<StoredReferenceImage, "imageUrl"> & { imageKey: string };
type PersistedArchivedReferenceGroup = Omit<ArchivedReferenceGroup, "imageUrlsByCameraId" | "images"> & {
  imageKeysByCameraId: Record<number, string>;
  images: Array<PersistedReferenceImage & { cameraId: number }>;
};
type PersistedReferenceState = {
  version: 1;
  activeImages: Array<PersistedReferenceImage & { cameraId: number }>;
  archives: PersistedArchivedReferenceGroup[];
  blobs: Array<[string, Blob]>;
};

void hydratePersistedReferenceState();

export function stageReferenceBundleImages(
  messageId: string,
  bundle: ClientReferenceBundlePayload,
  fallbackImageUrlsByCameraId: Record<number, string> = {},
) {
  pendingReferenceBundles.set(messageId, {
    bundle,
    fallbackImageUrlsByCameraId: { ...fallbackImageUrlsByCameraId },
  });
}

export function resolveReferenceBundleImages(messageId: string, accepted: boolean) {
  const pendingBundle = pendingReferenceBundles.get(messageId);
  if (!pendingBundle) {
    return;
  }
  pendingReferenceBundles.delete(messageId);

  if (accepted) {
    commitReferenceBundleImages(
      pendingBundle.bundle,
      pendingBundle.fallbackImageUrlsByCameraId,
      pendingBundle.roiPointsByCameraId,
      pendingBundle.jointCameraId,
      pendingBundle.jointRoiPoints,
    );
  }
}

export function stageReferenceBundleContours(
  messageId: string,
  roiPointsByCameraId: Record<number, InterestPointNorm[]>,
  jointCameraId: number,
  jointRoiPoints: InterestPointNorm[],
) {
  const pendingBundle = pendingReferenceBundles.get(messageId);
  if (!pendingBundle) {
    return;
  }

  pendingBundle.roiPointsByCameraId = Object.fromEntries(
    Object.entries(roiPointsByCameraId).map(([cameraId, points]) => [
      cameraId,
      copyRoiPoints(points),
    ]),
  );
  pendingBundle.jointCameraId = jointCameraId;
  pendingBundle.jointRoiPoints = copyRoiPoints(jointRoiPoints);
}

export function commitReferenceBundleImages(
  bundle: ClientReferenceBundlePayload,
  fallbackImageUrlsByCameraId: Record<number, string> = {},
  roiPointsByCameraId?: Record<number, InterestPointNorm[]>,
  jointCameraId?: number,
  jointRoiPoints?: InterestPointNorm[],
) {
  const nextReferenceImagesByCameraId = new Map(referenceImagesByCameraId);
  const nextReferenceImageVersion = referenceImageVersion + 1;
  const updatedCameraIds = new Set<number>();

  bundle.views.forEach((view, viewIndex) => {
    const cameraId = view.frame.camera_id;
    updatedCameraIds.add(cameraId);
    const baseImageUrl =
      fallbackImageUrlsByCameraId[cameraId] ??
      createFrameImageUrl(view.frame);

    if (baseImageUrl) {
      const referenceImage = {
        imageUrl: versionReferenceImageUrl(baseImageUrl, nextReferenceImageVersion),
        frame: createDurableReferenceFrame(view.frame),
        productType: bundle.product_type,
        committedAtMs: Date.now(),
        roiPoints: copyRoiPoints(roiPointsByCameraId?.[cameraId] ?? view.interest_polygon_norm),
        jointRoiPoints:
          cameraId === jointCameraId && jointRoiPoints
            ? copyRoiPoints(jointRoiPoints)
            : viewIndex === bundle.joint_view_index && view.joint_roi
              ? createNormalizedRoiPolygon(view.joint_roi, view.frame.width, view.frame.height)
              : undefined,
        fpZones: copyFpZonesForCamera(bundle.fp_zones, cameraId),
      };

      nextReferenceImagesByCameraId.set(cameraId, referenceImage);
    }
  });

  if (nextReferenceImagesByCameraId.size === 0) {
    return;
  }

  archiveCurrentReferenceGroup([...updatedCameraIds]);

  referenceImagesByCameraId.forEach((referenceImage, cameraId) => {
    if (!updatedCameraIds.has(cameraId)) {
      return;
    }
    if (
      referenceImage.imageUrl.startsWith("blob:") &&
      !isImageUrlArchived(referenceImage.imageUrl) &&
      !Array.from(nextReferenceImagesByCameraId.values()).some(
        (nextReferenceImage) => nextReferenceImage.imageUrl.startsWith(referenceImage.imageUrl),
      )
    ) {
      URL.revokeObjectURL(referenceImage.imageUrl.split("?")[0]);
    }
  });
  referenceImagesByCameraId.clear();
  nextReferenceImagesByCameraId.forEach((referenceImage, cameraId) => {
    referenceImagesByCameraId.set(cameraId, referenceImage);
  });
  referenceImageVersion = nextReferenceImageVersion;

  // Keep the newly accepted reference selectable as well as the superseded one.
  archiveCurrentReferenceGroup([...updatedCameraIds]);
  queuePersistReferenceState();

  emitReferenceImageChange();
}

export function getReferenceImageUrl(cameraId?: number) {
  return getReferenceImage(cameraId)?.imageUrl;
}

export function getReferenceImage(cameraId?: number) {
  return cameraId === undefined ? undefined : referenceImagesByCameraId.get(cameraId);
}

export function getReferenceImagesSnapshot() {
  return Array.from(referenceImagesByCameraId.entries())
    .map(([cameraId, referenceImage]) => ({ cameraId, ...copyStoredReferenceImage(referenceImage) }))
    .sort((left, right) => left.cameraId - right.cameraId);
}

export function clearReferenceImages() {
  if (referenceImagesByCameraId.size === 0 && pendingReferenceBundles.size === 0) {
    return;
  }

  referenceImagesByCameraId.forEach((referenceImage) => {
    if (referenceImage.imageUrl.startsWith("blob:") && !isImageUrlArchived(referenceImage.imageUrl)) {
      URL.revokeObjectURL(referenceImage.imageUrl.split("?")[0]);
    }
  });
  referenceImagesByCameraId.clear();
  pendingReferenceBundles.clear();
  referenceImageVersion += 1;
  queuePersistReferenceState();
  emitReferenceImageChange();
}

export function getArchivedReferenceGroups() {
  if (archivedReferenceGroupsSnapshotVersion !== archivedReferenceGroupsVersion) {
    archivedReferenceGroupsSnapshot = archivedReferenceGroups.map(copyArchivedReferenceGroup);
    archivedReferenceGroupsSnapshotVersion = archivedReferenceGroupsVersion;
  }

  return archivedReferenceGroupsSnapshot;
}

export function getArchivedReferenceGroup(id: string) {
  const archive = archivedReferenceGroups.find((referenceGroup) => referenceGroup.id === id);
  return archive ? copyArchivedReferenceGroup(archive) : undefined;
}

export async function deleteArchivedReferenceGroup(id: string) {
  const archiveIndex = archivedReferenceGroups.findIndex((referenceGroup) => referenceGroup.id === id);
  if (archiveIndex < 0) {
    return;
  }

  const archive = archivedReferenceGroups[archiveIndex];
  const learnedCaseIds = [...new Set(Object.values(archive.learnedCaseIdsByCameraId).flat())];
  await Promise.all(
    learnedCaseIds.map(async (caseId) => {
      try {
        await orchestratorApi.deleteLearnedNormal(caseId);
      } catch (error) {
        if (!(error instanceof HttpError) || error.status !== 404) throw error;
      }
    }),
  );
  archivedReferenceGroups.splice(archiveIndex, 1);
  for (const imageUrl of Object.values(archive.imageUrlsByCameraId)) {
    if (imageUrl.startsWith("blob:") && !isImageUrlInUse(imageUrl)) {
      URL.revokeObjectURL(imageUrl);
    }
  }
  markArchivedReferenceGroupsChanged();
  queuePersistReferenceState();
  emitReferenceImageChange();
}

export function isReferenceImageUrlInUse(imageUrl: string) {
  return isImageUrlInUse(imageUrl);
}

export function subscribeReferenceImages(listener: ReferenceImageListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function archiveCurrentReferenceGroup(cameraIds: number[]) {
  const images = cameraIds
    .map((cameraId) => {
      const referenceImage = referenceImagesByCameraId.get(cameraId);
      return referenceImage ? { cameraId, ...copyStoredReferenceImage(referenceImage) } : null;
    })
    .filter((referenceImage): referenceImage is StoredReferenceImage & { cameraId: number } => Boolean(referenceImage));

  if (images.length === 0) {
    return;
  }

  const archive = createArchivedReferenceGroup(images);
  if (isDuplicateArchive(archive)) {
    return;
  }

  archivedReferenceGroups.unshift(archive);
  while (archivedReferenceGroups.length > MAX_ARCHIVED_REFERENCE_GROUPS) {
    const removedArchive = archivedReferenceGroups.pop();
    if (!removedArchive) {
      continue;
    }
    for (const imageUrl of Object.values(removedArchive.imageUrlsByCameraId)) {
      if (imageUrl.startsWith("blob:") && !isImageUrlInUse(imageUrl)) {
        URL.revokeObjectURL(imageUrl);
      }
    }
  }
  markArchivedReferenceGroupsChanged();
}

function createArchivedReferenceGroup(
  images: Array<StoredReferenceImage & { cameraId: number }>,
): ArchivedReferenceGroup {
  const sortedImages = [...images].sort((left, right) => left.cameraId - right.cameraId);
  const jointCameraId =
    sortedImages.find((image) => isValidJointRoiPolygon(image.jointRoiPoints))?.cameraId ?? sortedImages[0].cameraId;
  const jointViewIndex = Math.max(0, sortedImages.findIndex((image) => image.cameraId === jointCameraId));
  const jointFrame = sortedImages[jointViewIndex]?.frame ?? sortedImages[0].frame;
  const bundle: ClientReferenceBundlePayload = {
    product_type: sortedImages[0].productType,
    joint_view_index: jointViewIndex,
    heatmap_width: jointFrame.width,
    heatmap_height: jointFrame.height,
    views: sortedImages.map((image, viewIndex) => ({
      frame: { ...image.frame },
      interest_roi: createPixelRoiFromPolygon(image.roiPoints, image.frame.width, image.frame.height),
      interest_polygon_norm: copyRoiPoints(image.roiPoints),
      joint_roi:
        viewIndex === jointViewIndex && isValidJointRoiPolygon(image.jointRoiPoints)
          ? createPixelRoiFromPolygon(image.jointRoiPoints ?? [], image.frame.width, image.frame.height)
          : null,
      ...(viewIndex === jointViewIndex && isValidJointRoiPolygon(image.jointRoiPoints)
        ? { joint_roi_polygon_norm: copyRoiPoints(image.jointRoiPoints ?? []) }
        : {}),
    })),
    fp_zones: sortedImages.flatMap((image) => copyFpZonesForCamera(image.fpZones ?? [], image.cameraId)),
  };

  return {
    id: createArchiveId(sortedImages),
    createdAtMs: Date.now(),
    cameraIds: sortedImages.map((image) => image.cameraId),
    jointCameraId,
    bundle,
    imageUrlsByCameraId: Object.fromEntries(sortedImages.map((image) => [image.cameraId, image.imageUrl])),
    images: sortedImages.map((image) => ({ cameraId: image.cameraId, ...copyStoredReferenceImage(image) })),
    learnedCaseIdsByCameraId: {},
  };
}

function copyArchivedReferenceGroup(archive: ArchivedReferenceGroup): ArchivedReferenceGroup {
  return {
    id: archive.id,
    createdAtMs: archive.createdAtMs,
    cameraIds: [...archive.cameraIds],
    jointCameraId: archive.jointCameraId,
    bundle: {
      ...archive.bundle,
      views: archive.bundle.views.map((view) => ({
        frame: { ...view.frame },
        interest_roi: { ...view.interest_roi },
        interest_polygon_norm: copyRoiPoints(view.interest_polygon_norm),
        joint_roi: view.joint_roi ? { ...view.joint_roi } : view.joint_roi,
        ...(view.joint_roi_polygon_norm && view.joint_roi_polygon_norm.length >= 3
          ? { joint_roi_polygon_norm: copyRoiPoints(view.joint_roi_polygon_norm) }
          : {}),
      })),
      fp_zones: copyFpZones(archive.bundle.fp_zones),
    },
    imageUrlsByCameraId: { ...archive.imageUrlsByCameraId },
    images: archive.images.map((image) => ({ cameraId: image.cameraId, ...copyStoredReferenceImage(image) })),
    learnedCaseIdsByCameraId: Object.fromEntries(
      Object.entries(archive.learnedCaseIdsByCameraId).map(([cameraId, caseIds]) => [cameraId, [...caseIds]]),
    ),
  };
}

function copyStoredReferenceImage(referenceImage: StoredReferenceImage): StoredReferenceImage {
  return {
    imageUrl: referenceImage.imageUrl,
    frame: { ...referenceImage.frame },
    productType: referenceImage.productType,
    committedAtMs: referenceImage.committedAtMs,
    roiPoints: copyRoiPoints(referenceImage.roiPoints),
    jointRoiPoints: referenceImage.jointRoiPoints ? copyRoiPoints(referenceImage.jointRoiPoints) : undefined,
    fpZones: referenceImage.fpZones ? copyFpZones(referenceImage.fpZones) : undefined,
  };
}

function createPixelRoiFromPolygon(points: InterestPointNorm[], frameWidth: number, frameHeight: number) {
  if (points.length === 0) {
    return {
      x: 0,
      y: 0,
      width: Math.max(1, frameWidth),
      height: Math.max(1, frameHeight),
    };
  }

  const xs = points.map((point) => clamp01(point.x));
  const ys = points.map((point) => clamp01(point.y));
  const left = Math.min(...xs);
  const top = Math.min(...ys);
  const right = Math.max(...xs);
  const bottom = Math.max(...ys);

  return {
    x: Math.round(left * frameWidth),
    y: Math.round(top * frameHeight),
    width: Math.max(1, Math.round((right - left) * frameWidth)),
    height: Math.max(1, Math.round((bottom - top) * frameHeight)),
  };
}

function isDuplicateArchive(archive: ArchivedReferenceGroup) {
  return archivedReferenceGroups.some(
    (existingArchive) =>
      existingArchive.cameraIds.join(",") === archive.cameraIds.join(",") &&
      archive.cameraIds.every(
        (cameraId) => existingArchive.imageUrlsByCameraId[cameraId] === archive.imageUrlsByCameraId[cameraId],
      ),
  );
}

function isImageUrlArchived(imageUrl: string) {
  return archivedReferenceGroups.some((archive) =>
    Object.values(archive.imageUrlsByCameraId).some((archivedUrl) => archivedUrl === imageUrl),
  );
}

function isImageUrlInUse(imageUrl: string) {
  return (
    Array.from(referenceImagesByCameraId.values()).some((referenceImage) => referenceImage.imageUrl === imageUrl) ||
    isImageUrlArchived(imageUrl)
  );
}

function createArchiveId(images: Array<StoredReferenceImage & { cameraId: number }>) {
  const frameKey = images.map((image) => `${image.cameraId}:${image.frame.frame_id}`).join("|");
  return `${Date.now()}-${frameKey}-${Math.random().toString(16).slice(2)}`;
}

function markArchivedReferenceGroupsChanged() {
  archivedReferenceGroupsVersion += 1;
}

function copyRoiPoints(points: InterestPointNorm[]) {
  return points.map((point) => ({
    x: point.x,
    y: point.y,
  }));
}

export function updateReferenceFpZones(cameraIds: number[], zones: FpZoneNorm[]) {
  let changed = false;
  for (const cameraId of cameraIds) {
    const referenceImage = referenceImagesByCameraId.get(cameraId);
    if (!referenceImage) continue;
    referenceImagesByCameraId.set(cameraId, {
      ...referenceImage,
      fpZones: copyFpZonesForCamera(zones, cameraId),
    });
    changed = true;
  }
  if (changed) emitReferenceImageChange();
  if (changed) queuePersistReferenceState();
}

export function attachLearnedCasesToActiveReference(cameraId: number, caseIds: string[]) {
  if (caseIds.length === 0) return;
  const activeImage = referenceImagesByCameraId.get(cameraId);
  if (!activeImage) return;
  const archive = archivedReferenceGroups.find((candidate) =>
    candidate.images.some(
      (image) => image.cameraId === cameraId && image.frame.frame_id === activeImage.frame.frame_id,
    ),
  );
  if (!archive) return;

  archive.learnedCaseIdsByCameraId[cameraId] = [
    ...new Set([...(archive.learnedCaseIdsByCameraId[cameraId] ?? []), ...caseIds]),
  ];
  markArchivedReferenceGroupsChanged();
  queuePersistReferenceState();
  emitReferenceImageChange();
}

export function detachLearnedCaseFromReferences(caseId: string) {
  let changed = false;
  for (const archive of archivedReferenceGroups) {
    for (const [cameraId, caseIds] of Object.entries(archive.learnedCaseIdsByCameraId)) {
      const nextIds = caseIds.filter((id) => id !== caseId);
      if (nextIds.length === caseIds.length) continue;
      archive.learnedCaseIdsByCameraId[Number(cameraId)] = nextIds;
      changed = true;
    }
  }
  if (!changed) return;
  markArchivedReferenceGroupsChanged();
  queuePersistReferenceState();
  emitReferenceImageChange();
}

function createDurableReferenceFrame(frame: ShmFrameRefData): ShmFrameRefData {
  return {
    ...frame,
    shm_name: `/iml_ref_cam${frame.camera_id}`,
    shm_offset: 0,
    expires_at_ms: undefined,
    ttl_ms: undefined,
    read_token: undefined,
  };
}

function queuePersistReferenceState() {
  if (typeof indexedDB === "undefined") return;
  // Blob fetches start before obsolete object URLs can be revoked.
  const snapshot = createPersistedReferenceState();
  persistenceChain = persistenceChain
    .catch(() => undefined)
    .then(async () => writePersistedReferenceState(await snapshot))
    .catch((error) => console.warn("Не удалось сохранить библиотеку эталонов", error));
}

async function createPersistedReferenceState(): Promise<PersistedReferenceState> {
  const activeImages = getReferenceImagesSnapshot();
  const archives = archivedReferenceGroups.map(copyArchivedReferenceGroup);
  const urls = new Set<string>();
  activeImages.forEach((image) => urls.add(image.imageUrl));
  archives.forEach((archive) => Object.values(archive.imageUrlsByCameraId).forEach((url) => urls.add(url)));
  const blobs = await Promise.all(
    [...urls].map(async (url): Promise<[string, Blob] | null> => {
      try {
        const response = await fetch(url, { cache: "no-store" });
        return response.ok ? [url, await response.blob()] : null;
      } catch {
        return null;
      }
    }),
  );

  return {
    version: 1,
    activeImages: activeImages.map(({ cameraId, imageUrl, ...image }) => ({ cameraId, imageKey: imageUrl, ...image })),
    archives: archives.map((archive) => ({
      id: archive.id,
      createdAtMs: archive.createdAtMs,
      cameraIds: archive.cameraIds,
      jointCameraId: archive.jointCameraId,
      bundle: archive.bundle,
      imageKeysByCameraId: { ...archive.imageUrlsByCameraId },
      images: archive.images.map(({ cameraId, imageUrl, ...image }) => ({ cameraId, imageKey: imageUrl, ...image })),
      learnedCaseIdsByCameraId: archive.learnedCaseIdsByCameraId,
    })),
    blobs: blobs.filter((entry): entry is [string, Blob] => entry !== null),
  };
}

async function hydratePersistedReferenceState() {
  if (typeof indexedDB === "undefined") return;
  try {
    const state = await readPersistedReferenceState();
    if (!state || state.version !== 1 || referenceImagesByCameraId.size > 0) return;
    const objectUrls = new Map(state.blobs.map(([key, blob]) => [key, URL.createObjectURL(blob)]));
    const resolveUrl = (key: string) => objectUrls.get(key);

    for (const persisted of state.activeImages) {
      const imageUrl = resolveUrl(persisted.imageKey);
      if (!imageUrl) continue;
      referenceImagesByCameraId.set(persisted.cameraId, restorePersistedReferenceImage(persisted, imageUrl));
    }
    archivedReferenceGroups.splice(0, archivedReferenceGroups.length);
    for (const archive of state.archives) {
      const images = archive.images.flatMap(({ cameraId, imageKey, ...image }) => {
        const imageUrl = resolveUrl(imageKey);
        return imageUrl ? [{ cameraId, ...image, imageUrl, frame: createDurableReferenceFrame(image.frame) }] : [];
      });
      if (images.length !== archive.images.length) continue;
      archivedReferenceGroups.push({
        id: archive.id,
        createdAtMs: archive.createdAtMs,
        cameraIds: [...archive.cameraIds],
        jointCameraId: archive.jointCameraId,
        bundle: {
          ...archive.bundle,
          views: archive.bundle.views.map((view) => ({ ...view, frame: createDurableReferenceFrame(view.frame) })),
        },
        imageUrlsByCameraId: Object.fromEntries(images.map((image) => [image.cameraId, image.imageUrl])),
        images,
        learnedCaseIdsByCameraId: Object.fromEntries(
          Object.entries(archive.learnedCaseIdsByCameraId ?? {}).map(([cameraId, caseIds]) => [cameraId, [...caseIds]]),
        ),
      });
    }
    referenceImageVersion += 1;
    markArchivedReferenceGroupsChanged();
    emitReferenceImageChange();
  } catch (error) {
    console.warn("Не удалось загрузить локальную библиотеку эталонов", error);
  }
}

function restorePersistedReferenceImage(persisted: PersistedReferenceImage, imageUrl: string): StoredReferenceImage {
  return {
    imageUrl,
    frame: createDurableReferenceFrame(persisted.frame),
    productType: persisted.productType,
    committedAtMs: persisted.committedAtMs,
    roiPoints: copyRoiPoints(persisted.roiPoints),
    jointRoiPoints: persisted.jointRoiPoints ? copyRoiPoints(persisted.jointRoiPoints) : undefined,
    fpZones: persisted.fpZones ? copyFpZones(persisted.fpZones) : undefined,
  };
}

function openReferenceDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(REFERENCE_DB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(REFERENCE_DB_STORE)) {
        request.result.createObjectStore(REFERENCE_DB_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function writePersistedReferenceState(state: PersistedReferenceState) {
  const db = await openReferenceDb();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(REFERENCE_DB_STORE, "readwrite");
    transaction.objectStore(REFERENCE_DB_STORE).put(state, REFERENCE_DB_KEY);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
  db.close();
}

async function readPersistedReferenceState(): Promise<PersistedReferenceState | undefined> {
  const db = await openReferenceDb();
  const state = await new Promise<PersistedReferenceState | undefined>((resolve, reject) => {
    const request = db.transaction(REFERENCE_DB_STORE, "readonly").objectStore(REFERENCE_DB_STORE).get(REFERENCE_DB_KEY);
    request.onsuccess = () => resolve(request.result as PersistedReferenceState | undefined);
    request.onerror = () => reject(request.error);
  });
  db.close();
  return state;
}

function copyFpZones(zones: FpZoneNorm[]) {
  return zones.map((zone) => ({
    ...zone,
    points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({
      x: point.x,
      y: point.y,
    })),
  }));
}

function copyFpZonesForCamera(zones: FpZoneNorm[], cameraId: number) {
  return copyFpZones(zones)
    .filter((zone) => zone.camera_id === undefined || zone.camera_id === cameraId)
    .map((zone) => ({
      ...zone,
      camera_id: cameraId,
    }));
}

function createNormalizedRoiPolygon(
  roi: { x: number; y: number; width: number; height: number },
  frameWidth: number,
  frameHeight: number,
) {
  if (frameWidth <= 0 || frameHeight <= 0) {
    return undefined;
  }

  const left = clamp01(roi.x / frameWidth);
  const top = clamp01(roi.y / frameHeight);
  const right = clamp01((roi.x + roi.width) / frameWidth);
  const bottom = clamp01((roi.y + roi.height) / frameHeight);

  return [
    { x: left, y: top },
    { x: right, y: top },
    { x: right, y: bottom },
    { x: left, y: bottom },
  ];
}

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}

function createFrameImageUrl(frame: ShmFrameRefData) {
  const imagePath = frame.http_path;

  if (imagePath) {
    return orchestratorApi.imageUrl(imagePath, frame.frame_id);
  }

  return undefined;
}

function versionReferenceImageUrl(imageUrl: string, version: number) {
  if (imageUrl.startsWith("blob:")) {
    return imageUrl;
  }

  const separator = imageUrl.includes("?") ? "&" : "?";
  return `${imageUrl}${separator}reference_ts=${version}`;
}

function emitReferenceImageChange() {
  for (const listener of listeners) {
    listener();
  }
}
