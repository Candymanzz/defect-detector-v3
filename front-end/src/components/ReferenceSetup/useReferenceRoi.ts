import { useState } from "react";
import type { InterestPointNorm } from "../../shared/ws";
import { isValidRoiPolygon } from "./referenceRoi";

export type ReferenceRoiEditMode = "interest" | "joint";

export function useReferenceRoi(cameraIds: number[], initialSelectedCameraId: number | null = null) {
  const initialCameraId = resolveCameraId(cameraIds, initialSelectedCameraId);
  const [roiPolygonsByCameraId, setRoiPolygonsByCameraId] = useState<Record<number, InterestPointNorm[]>>({});
  const [jointRoiPolygon, setJointRoiPolygon] = useState<InterestPointNorm[]>([]);
  const [selectedCameraIdState, setSelectedCameraIdState] = useState(initialCameraId);
  const [selectedRoiMode, setSelectedRoiMode] = useState<ReferenceRoiEditMode>("interest");
  const selectedCameraId = resolveCameraId(cameraIds, selectedCameraIdState);
  const jointCameraId = cameraIds[0] ?? 0;
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois =
    cameraIds.length > 0 && cameraIds.every((cameraId) => isValidRoiPolygon(roiPolygonsByCameraId[cameraId]));
  const hasRequiredJointRoi = isValidRoiPolygon(jointRoiPolygon);
  const jointViewIndex = cameraIds.indexOf(jointCameraId);

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    const targetCameraId = resolveCameraId(cameraIds, cameraId);

    setRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [targetCameraId]: copyRoiPolygon(points),
    }));
  };

  const setJointRoi = (points: InterestPointNorm[]) => {
    setJointRoiPolygon(copyRoiPolygon(points));
  };

  const setSelectedCameraId = (cameraId: number) => {
    const nextCameraId = resolveCameraId(cameraIds, cameraId);
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
