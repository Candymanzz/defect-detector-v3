import { appEnv } from "./config/env";

export const CAMERAS_PER_BUCKET = 5;

export type InspectionBucket = {
  key: string;
  phaseId: number;
  groupId: number;
  cameraIds: number[];
  label: string;
};

/**
 * phaseCount=1 (станок на 2 изделия): ведра 1–2. phaseCount=2 (4 изделия): phase 0 → ведра 1–2, phase 1 → ведра 3–4,
 * одни camera id в обеих фазах. По умолчанию из VITE_BUCKET_PHASE_COUNT (1 или 2).
 */
export function createInspectionBuckets(
  cameraIds: number[],
  phaseCount: number = appEnv.bucketPhaseCount,
): InspectionBucket[] {
  const uniqueCameraIds = [...new Set(cameraIds)].sort((left, right) => left - right);
  const cameraSets: number[][] = [];
  for (let index = 0; index < uniqueCameraIds.length; index += CAMERAS_PER_BUCKET) {
    cameraSets.push(uniqueCameraIds.slice(index, index + CAMERAS_PER_BUCKET));
  }
  const sets = cameraSets.slice(0, 2);

  return Array.from({ length: phaseCount }, (_, phaseId) => phaseId).flatMap((phaseId) =>
    sets.map((groupCameraIds, cameraSetIndex) => {
      const groupId = phaseId * sets.length + cameraSetIndex;
      return {
        key: `${phaseId}:${groupId}`,
        phaseId,
        groupId,
        cameraIds: groupCameraIds,
        label: `Ведро ${groupId + 1}`,
      };
    }),
  );
}

export function bucketIndexForCamera(buckets: InspectionBucket[], cameraId: number, phaseId = 0) {
  const index = buckets.findIndex(
    (bucket) => bucket.phaseId === phaseId && bucket.cameraIds.includes(cameraId),
  );
  return index < 0 ? 0 : index;
}
