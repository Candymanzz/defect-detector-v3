import { useState } from "react";
import { getReferenceImage } from "../../shared/referenceImages";
import type { InterestPointNorm } from "../../shared/ws";
import { isValidRoiPolygon } from "./referenceRoi";

export type ReferenceRoiEditMode = "interest" | "joint";

export function useReferenceRoi(
  cameraIds: number[],
  cameraGroups: number[][],
  activeGroupIndex: number,
  initialSelectedCameraId: number | null = null,
) {
  const activeCameraIds = cameraGroups[activeGroupIndex] ?? cameraIds;
  const initialCameraId = resolveCameraId(activeCameraIds, initialSelectedCameraId);
  const [editedRoiPolygonsByCameraId, setEditedRoiPolygonsByCameraId] = useState<
    Record<number, InterestPointNorm[]>
  >({});
  const [editedJointRoiPolygonsByGroupKey, setEditedJointRoiPolygonsByGroupKey] = useState<
    Record<string, InterestPointNorm[]>
  >({});
  const [jointCameraIdsByGroupKey, setJointCameraIdsByGroupKey] = useState<Record<string, number>>({});
  const [selectedCameraIdState, setSelectedCameraIdState] = useState(initialCameraId);
  const [selectedRoiMode, setSelectedRoiMode] = useState<ReferenceRoiEditMode>("interest");
  const roiPolygonsByCameraId = mergeStoredCameraRois(cameraIds, editedRoiPolygonsByCameraId);
  const jointRoiPolygonsByKey = mergeStoredJointRois(cameraGroups, editedJointRoiPolygonsByGroupKey);
  const selectedCameraId = resolveCameraId(activeCameraIds, selectedCameraIdState);
  const jointGroupKey = createGroupKey(activeCameraIds);
  const jointCameraId = resolveCameraId(
    activeCameraIds,
    jointCameraIdsByGroupKey[jointGroupKey] ?? findStoredJointCameraId(activeCameraIds),
  );
  const jointRoiKey = createJointRoiKey(activeCameraIds, jointCameraId);
  const jointRoiPolygon = jointRoiPolygonsByKey[jointRoiKey] ?? [];
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois =
    activeCameraIds.length > 0 && activeCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));
  const hasJointRoi = isValidRoiPolygon(jointRoiPolygon);
  const jointViewIndex = activeCameraIds.indexOf(jointCameraId);

  const getJointCameraIdForCameraIds = (targetCameraIds: number[]) => {
    const groupKey = createGroupKey(targetCameraIds);
    return resolveCameraId(
      targetCameraIds,
      jointCameraIdsByGroupKey[groupKey] ?? findStoredJointCameraId(targetCameraIds),
    );
  };

  const getJointRoiPolygonForCameraIds = (targetCameraIds: number[]) => {
    const targetJointCameraId = getJointCameraIdForCameraIds(targetCameraIds);
    return jointRoiPolygonsByKey[createJointRoiKey(targetCameraIds, targetJointCameraId)] ?? [];
  };

  const hasRequiredRoisForCameraIds = (targetCameraIds: number[]) =>
    targetCameraIds.length > 0 &&
    targetCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    const targetCameraId = resolveCameraId(cameraIds, cameraId);

    setEditedRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [targetCameraId]: copyRoiPolygon(points),
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
    setJointRoiPolygon: setJointRoi,
    setRoiPolygonForCamera,
    setSelectedCameraId,
  };
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
  editedRois: Record<number, InterestPointNorm[]>,
) {
  const merged: Record<number, InterestPointNorm[]> = {};

  for (const cameraId of cameraIds) {
    const editedPoints = editedRois[cameraId];
    const storedPoints = getReferenceImage(cameraId)?.roiPoints;
    const points = editedPoints ?? storedPoints;

    if (points) {
      merged[cameraId] = copyRoiPolygon(points);
    }
  }

  return merged;
}

function mergeStoredJointRois(
  cameraGroups: number[][],
  editedRois: Record<string, InterestPointNorm[]>,
) {
  const merged: Record<string, InterestPointNorm[]> = {};

  for (const groupCameraIds of cameraGroups) {
    for (const cameraId of groupCameraIds) {
      const roiKey = createJointRoiKey(groupCameraIds, cameraId);
      const editedPoints = editedRois[roiKey];
      const storedPoints = getReferenceImage(cameraId)?.jointRoiPoints;
      const points = editedPoints ?? storedPoints;

      if (points) {
        merged[roiKey] = copyRoiPolygon(points);
      }
    }
  }

  return merged;
}

function findStoredJointCameraId(cameraIds: number[]) {
  return cameraIds.find((cameraId) => isValidRoiPolygon(getReferenceImage(cameraId)?.jointRoiPoints)) ?? null;
}

function createGroupKey(cameraIds: number[]) {
  return cameraIds.join(",");
}

function createJointRoiKey(cameraIds: number[], cameraId: number) {
  return `${createGroupKey(cameraIds)}:${cameraId}`;
}
