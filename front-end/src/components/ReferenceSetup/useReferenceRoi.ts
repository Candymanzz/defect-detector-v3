import { useState } from "react";
import { getReferenceImage } from "../../shared/referenceImages";
import type { InspectionBucket } from "../../shared/inspectionBuckets";
import type { InterestPointNorm } from "../../shared/ws";
import { isValidJointRoiPolygon, isValidRoiPolygon } from "./referenceRoi";

export type ReferenceRoiEditMode = "interest" | "joint";

export function useReferenceRoi(
  cameraIds: number[],
  cameraGroups: InspectionBucket[],
  activeGroupIndex: number,
  initialSelectedCameraId: number | null = null,
  useStoredRois = true,
) {
  const activeGroup = cameraGroups[activeGroupIndex];
  const activeCameraIds = activeGroup?.cameraIds ?? cameraIds;
  const activeGroupId = activeGroup?.groupId ?? 0;
  const activePhaseId = activeGroup?.phaseId ?? 0;
  const initialCameraId = resolveCameraId(activeCameraIds, initialSelectedCameraId);
  const [editedRoiPolygonsByCameraId, setEditedRoiPolygonsByCameraId] = useState<
    Record<string, InterestPointNorm[]>
  >({});
  const [editedJointRoiPolygonsByGroupKey, setEditedJointRoiPolygonsByGroupKey] = useState<
    Record<string, InterestPointNorm[]>
  >({});
  const [jointCameraIdsByGroupKey, setJointCameraIdsByGroupKey] = useState<Record<string, number>>({});
  const [selectedCameraIdState, setSelectedCameraIdState] = useState(initialCameraId);
  const [selectedRoiMode, setSelectedRoiMode] = useState<ReferenceRoiEditMode>("interest");
  const roiPolygonsByCameraId = useStoredRois
    ? mergeStoredCameraRois(activeCameraIds, editedRoiPolygonsByCameraId, activePhaseId, activeGroupId)
    : copyEditedCameraRois(activeCameraIds, editedRoiPolygonsByCameraId, activeGroupId);
  const jointRoiPolygonsByKey = useStoredRois
    ? mergeStoredJointRois(cameraGroups, editedJointRoiPolygonsByGroupKey)
    : copyEditedJointRois(editedJointRoiPolygonsByGroupKey);
  const selectedCameraId = resolveCameraId(activeCameraIds, selectedCameraIdState);
  const jointGroupKey = createGroupKey(activeGroup);
  const jointCameraId = resolveCameraId(
    activeCameraIds,
    jointCameraIdsByGroupKey[jointGroupKey] ?? findStoredJointCameraId(activeGroup),
  );
  const jointRoiKey = createJointRoiKey(activeGroup, jointCameraId);
  const jointRoiPolygon = jointRoiPolygonsByKey[jointRoiKey] ?? [];
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois =
    activeCameraIds.length > 0 && activeCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));
  const hasJointRoi = isValidJointRoiPolygon(jointRoiPolygon);
  const jointViewIndex = activeCameraIds.indexOf(jointCameraId);

  const getJointCameraIdForCameraIds = (targetCameraIds: number[]) => {
    const group = cameraGroups.find((candidate) => sameCameras(candidate.cameraIds, targetCameraIds) && candidate.groupId === activeGroupId)
      ?? activeGroup;
    const groupKey = createGroupKey(group);
    return resolveCameraId(
      targetCameraIds,
      jointCameraIdsByGroupKey[groupKey] ?? findStoredJointCameraId(group),
    );
  };

  const getJointRoiPolygonForCameraIds = (targetCameraIds: number[]) => {
    const group =
      cameraGroups.find(
        (candidate) => sameCameras(candidate.cameraIds, targetCameraIds) && candidate.groupId === activeGroupId,
      ) ?? activeGroup;
    const targetJointCameraId = getJointCameraIdForCameraIds(targetCameraIds);
    return jointRoiPolygonsByKey[createJointRoiKey(group, targetJointCameraId)] ?? [];
  };

  const hasRequiredRoisForCameraIds = (targetCameraIds: number[]) =>
    targetCameraIds.length > 0 &&
    targetCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    const targetCameraId = resolveCameraId(cameraIds, cameraId);

    setEditedRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [roiEditKey(activeGroupId, targetCameraId)]: copyRoiPolygon(points),
    }));
  };

  const setJointRoi = (points: InterestPointNorm[]) => {
    setEditedJointRoiPolygonsByGroupKey((previous) => ({
      ...previous,
      [jointRoiKey]: copyRoiPolygon(points),
    }));
  };

  const setSelectedCameraId = (cameraId: number) => {
    const nextCameraId = resolveCameraId(activeCameraIds, cameraId);
    setSelectedCameraIdState(nextCameraId);
    setSelectedRoiMode("interest");
  };

  const selectJointRoi = (cameraId: number) => {
    const nextCameraId = resolveCameraId(activeCameraIds, cameraId);
    setJointCameraIdsByGroupKey((previous) => ({
      ...previous,
      [jointGroupKey]: nextCameraId,
    }));
    setSelectedCameraIdState(nextCameraId);
    setSelectedRoiMode("joint");
  };

  const resetEditedRoisForCameraIds = (targetCameraIds: number[]) => {
    const targetCameraIdSet = new Set(targetCameraIds);
    setEditedRoiPolygonsByCameraId((previous) =>
      Object.fromEntries(
        Object.entries(previous).filter(([roiKey]) => {
          const cameraId = Number(roiKey.split(":")[1]);
          return !roiKey.startsWith(`${activeGroupId}:`) || !targetCameraIdSet.has(cameraId);
        }),
      ),
    );
    setEditedJointRoiPolygonsByGroupKey((previous) =>
      Object.fromEntries(
        Object.entries(previous).filter(([roiKey]) => !roiKey.startsWith(`${jointGroupKey}:`)),
      ),
    );
  };

  return {
    jointViewIndex,
    jointCameraId,
    activeCameraIds,
    hasSelectedCameraRoi,
    hasRequiredCameraRois,
    hasJointRoi,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    selectedCameraId,
    selectedRoiMode,
    getJointRoiPolygonForCameraIds,
    getJointCameraIdForCameraIds,
    hasRequiredRoisForCameraIds,
    selectJointRoi,
    resetEditedRoisForCameraIds,
    setJointRoiPolygon: setJointRoi,
    setRoiPolygonForCamera,
    setSelectedCameraId,
  };
}

function copyEditedCameraRois(
  cameraIds: number[],
  editedRois: Record<string, InterestPointNorm[]>,
  groupId: number,
) {
  const copied: Record<number, InterestPointNorm[]> = {};
  for (const cameraId of cameraIds) {
    const editedPoints = editedRois[roiEditKey(groupId, cameraId)];
    if (editedPoints) {
      copied[cameraId] = copyRoiPolygon(editedPoints);
    }
  }
  return copied;
}

function copyEditedJointRois(editedRois: Record<string, InterestPointNorm[]>) {
  return Object.fromEntries(
    Object.entries(editedRois).map(([roiKey, points]) => [roiKey, copyRoiPolygon(points)]),
  );
}

function copyRoiPolygon(points: InterestPointNorm[]) {
  return points.map((point) => ({
    x: point.x,
    y: point.y,
  }));
}

function resolveCameraId(cameraIds: number[], cameraId: number | null) {
  if (cameraId !== null && cameraIds.includes(cameraId)) {
    return cameraId;
  }

  return cameraIds[0] ?? cameraId ?? 0;
}

function mergeStoredCameraRois(
  cameraIds: number[],
  editedRois: Record<string, InterestPointNorm[]>,
  phaseId: number,
  groupId: number,
) {
  const merged: Record<number, InterestPointNorm[]> = {};

  for (const cameraId of cameraIds) {
    const editedPoints = editedRois[roiEditKey(groupId, cameraId)];
    const storedPoints = getReferenceImage(cameraId, phaseId, groupId)?.roiPoints;
    const points = editedPoints ?? storedPoints;

    if (points) {
      merged[cameraId] = copyRoiPolygon(points);
    }
  }

  return merged;
}

function mergeStoredJointRois(
  cameraGroups: InspectionBucket[],
  editedRois: Record<string, InterestPointNorm[]>,
) {
  const merged: Record<string, InterestPointNorm[]> = {};

  for (const group of cameraGroups) {
    for (const cameraId of group.cameraIds) {
      const roiKey = createJointRoiKey(group, cameraId);
      const editedPoints = editedRois[roiKey];
      const storedPoints = getReferenceImage(cameraId, group.phaseId, group.groupId)?.jointRoiPoints;
      const points = editedPoints ?? storedPoints;

      if (points) {
        merged[roiKey] = copyRoiPolygon(points);
      }
    }
  }

  return merged;
}

function findStoredJointCameraId(group: InspectionBucket | undefined) {
  if (!group) {
    return null;
  }
  return (
    group.cameraIds.find((cameraId) =>
      isValidJointRoiPolygon(getReferenceImage(cameraId, group.phaseId, group.groupId)?.jointRoiPoints),
    ) ?? null
  );
}

function createGroupKey(group: InspectionBucket | undefined) {
  return group ? `${group.phaseId}:${group.groupId}` : "";
}

function createJointRoiKey(group: InspectionBucket | undefined, cameraId: number) {
  return `${createGroupKey(group)}:${cameraId}`;
}

function roiEditKey(groupId: number, cameraId: number) {
  return `${groupId}:${cameraId}`;
}

function sameCameras(left: number[], right: number[]) {
  return left.length === right.length && left.every((cameraId, index) => cameraId === right[index]);
}
