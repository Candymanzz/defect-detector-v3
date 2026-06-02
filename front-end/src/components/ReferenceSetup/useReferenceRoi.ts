import { useState } from "react";
import type { InterestPointNorm } from "../../shared/ws";
import { clampViewIndex, isValidRoiPolygon } from "./referenceRoi";

export function useReferenceRoi(initialJointViewIndex: number | null = null) {
  const initialCameraId = clampViewIndex(initialJointViewIndex ?? 0);
  const [jointViewIndex, setJointViewIndexState] = useState(initialCameraId);
  const [roiPolygonsByCameraId, setRoiPolygonsByCameraId] = useState<Record<number, InterestPointNorm[]>>({});
  const [selectedCameraId, setSelectedCameraIdState] = useState(initialCameraId);
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    setRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [cameraId]: points,
    }));
  };

  const setSelectedCameraId = (cameraId: number) => {
    const nextCameraId = clampViewIndex(cameraId);
    setSelectedCameraIdState(nextCameraId);
    setJointViewIndexState(nextCameraId);
  };

  const setJointViewIndex = (cameraId: number) => {
    const nextCameraId = clampViewIndex(cameraId);
    setJointViewIndexState(nextCameraId);
    setSelectedCameraIdState(nextCameraId);
  };

  return {
    jointViewIndex,
    hasSelectedCameraRoi,
    roiPolygonsByCameraId,
    selectedCameraId,
    setJointViewIndex,
    setRoiPolygonForCamera,
    setSelectedCameraId,
  };
}
