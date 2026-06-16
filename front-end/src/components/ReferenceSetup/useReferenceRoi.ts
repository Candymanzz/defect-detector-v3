import { useState } from "react";
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
  const [roiPolygonsByCameraId, setRoiPolygonsByCameraId] = useState<Record<number, InterestPointNorm[]>>({});
  const [jointRoiPolygonsByGroupKey, setJointRoiPolygonsByGroupKey] = useState<Record<string, InterestPointNorm[]>>({});
  const [selectedCameraIdState, setSelectedCameraIdState] = useState(initialCameraId);
  const [selectedRoiMode, setSelectedRoiMode] = useState<ReferenceRoiEditMode>("interest");
  const selectedCameraId = resolveCameraId(activeCameraIds, selectedCameraIdState);
  const jointCameraId = activeCameraIds[0] ?? 0;
  const jointGroupKey = createGroupKey(activeCameraIds);
  const jointRoiPolygon = jointRoiPolygonsByGroupKey[jointGroupKey] ?? [];
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois =
    activeCameraIds.length > 0 && activeCameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));
  const hasRequiredJointRoi = isValidRoiPolygon(jointRoiPolygon);
  const jointViewIndex = activeCameraIds.indexOf(jointCameraId);

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    const targetCameraId = resolveCameraId(cameraIds, cameraId);

    setRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [targetCameraId]: copyRoiPolygon(points),
    }));
  };

  const setJointRoi = (points: InterestPointNorm[]) => {
    setJointRoiPolygonsByGroupKey((previous) => ({
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

function createGroupKey(cameraIds: number[]) {
  return cameraIds.join(",");
}
