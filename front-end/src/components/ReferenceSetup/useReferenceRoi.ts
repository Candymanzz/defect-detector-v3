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
  const [selectedCameraIdState, setSelectedCameraIdState] = useState(initialCameraId);
  const [selectedRoiMode, setSelectedRoiMode] = useState<ReferenceRoiEditMode>("interest");
  const roiPolygonsByCameraId = mergeStoredCameraRois(cameraIds, editedRoiPolygonsByCameraId);
  const jointRoiPolygonsByGroupKey = mergeStoredJointRois(cameraGroups, editedJointRoiPolygonsByGroupKey);
  const selectedCameraId = resolveCameraId(activeCameraIds, selectedCameraIdState);
  const jointCameraId = activeCameraIds[0] ?? 0;
  const jointGroupKey = createGroupKey(activeCameraIds);
  const jointRoiPolygon = jointRoiPolygonsByGroupKey[jointGroupKey] ?? [];
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois =
    activeCameraIds.length > 0 && activeCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));
  const hasRequiredJointRoi = isValidRoiPolygon(jointRoiPolygon);
  const jointViewIndex = activeCameraIds.indexOf(jointCameraId);

  const getJointRoiPolygonForCameraIds = (targetCameraIds: number[]) =>
    jointRoiPolygonsByGroupKey[createGroupKey(targetCameraIds)] ?? [];

  const hasRequiredRoisForCameraIds = (targetCameraIds: number[]) =>
    targetCameraIds.length > 0 &&
    targetCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId])) &&
    isValidRoiPolygon(getJointRoiPolygonForCameraIds(targetCameraIds));

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
      [jointGroupKey]: copyRoiPolygon(points),
    }));
  };

  const setSelectedCameraId = (cameraId: number) => {
    const nextCameraId = resolveCameraId(activeCameraIds, cameraId);
    setSelectedCameraIdState(nextCameraId);
    setSelectedRoiMode("interest");
  };

  const selectJointRoi = () => {
    setSelectedCameraIdState(jointCameraId);
    setSelectedRoiMode("joint");
  };

  return {
    jointViewIndex,
    jointCameraId,
    activeCameraIds,
    hasSelectedCameraRoi,
    hasRequiredCameraRois,
    hasRequiredJointRoi,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    selectedCameraId,
    selectedRoiMode,
    getJointRoiPolygonForCameraIds,
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
    const groupKey = createGroupKey(groupCameraIds);
    const editedPoints = editedRois[groupKey];
    const jointCameraId = groupCameraIds[0];
    const storedPoints = getReferenceImage(jointCameraId)?.jointRoiPoints;
    const points = editedPoints ?? storedPoints;

    if (points) {
      merged[groupKey] = copyRoiPolygon(points);
    }
  }

  return merged;
}

function createGroupKey(cameraIds: number[]) {
  return cameraIds.join(",");
}
