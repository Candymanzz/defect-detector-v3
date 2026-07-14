import { orchestratorApi } from "./api";
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
        frame: { ...view.frame },
        productType: bundle.product_type,
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

  emitReferenceImageChange();
}

export function getReferenceImageUrl(cameraId?: number) {
  return getReferenceImage(cameraId)?.imageUrl;
}

export function getReferenceImage(cameraId?: number) {
  return cameraId === undefined ? undefined : referenceImagesByCameraId.get(cameraId);
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

export function deleteArchivedReferenceGroup(id: string) {
  const archiveIndex = archivedReferenceGroups.findIndex((referenceGroup) => referenceGroup.id === id);
  if (archiveIndex < 0) {
    return;
  }

  const [archive] = archivedReferenceGroups.splice(archiveIndex, 1);
  for (const imageUrl of Object.values(archive.imageUrlsByCameraId)) {
    if (imageUrl.startsWith("blob:") && !isImageUrlInUse(imageUrl)) {
      URL.revokeObjectURL(imageUrl);
    }
  }
  markArchivedReferenceGroupsChanged();
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
  const jointCameraId = sortedImages.find((image) => isValidPolygon(image.jointRoiPoints))?.cameraId ?? sortedImages[0].cameraId;
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
        viewIndex === jointViewIndex && isValidPolygon(image.jointRoiPoints)
          ? createPixelRoiFromPolygon(image.jointRoiPoints ?? [], image.frame.width, image.frame.height)
          : null,
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
      })),
      fp_zones: copyFpZones(archive.bundle.fp_zones),
    },
    imageUrlsByCameraId: { ...archive.imageUrlsByCameraId },
    images: archive.images.map((image) => ({ cameraId: image.cameraId, ...copyStoredReferenceImage(image) })),
  };
}

function copyStoredReferenceImage(referenceImage: StoredReferenceImage): StoredReferenceImage {
  return {
    imageUrl: referenceImage.imageUrl,
    frame: { ...referenceImage.frame },
    productType: referenceImage.productType,
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

function isValidPolygon(points: InterestPointNorm[] | undefined) {
  return Boolean(points && points.length >= 3);
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
