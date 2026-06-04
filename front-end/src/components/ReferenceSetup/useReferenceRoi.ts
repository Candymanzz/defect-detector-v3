import { useState } from "react";
import type { InterestPointNorm } from "../../shared/ws";
import { REFERENCE_ACTIVE_CAMERA_ID, REFERENCE_CAMERA_IDS, REFERENCE_REQUIRED_CAMERA_IDS } from "./referenceConstants";
import { clampViewIndex, isValidRoiPolygon } from "./referenceRoi";

export function useReferenceRoi(initialJointViewIndex: number | null = null) {
  const initialCameraId = clampReferenceCameraId(initialJointViewIndex ?? REFERENCE_ACTIVE_CAMERA_ID);
  const [jointViewIndex, setJointViewIndexState] = useState(initialCameraId);
  const [roiPolygonsByCameraId, setRoiPolygonsByCameraId] = useState<Record<number, InterestPointNorm[]>>({});
  const [selectedCameraId, setSelectedCameraIdState] = useState(initialCameraId);
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois = REFERENCE_REQUIRED_CAMERA_IDS.every((cameraId) =>
    isValidRoiPolygon(roiPolygonsByCameraId[cameraId]),
  );

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    setRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [cameraId]: points,
    }));
  };

  const setSelectedCameraId = (cameraId: number) => {
    const nextCameraId = clampReferenceCameraId(cameraId);
    setSelectedCameraIdState(nextCameraId);
  };

  const setJointViewIndex = (cameraId: number) => {
    const nextCameraId = clampReferenceCameraId(cameraId);
    setJointViewIndexState(nextCameraId);
  };

  return {
    jointViewIndex,
    hasSelectedCameraRoi,
    hasRequiredCameraRois,
    roiPolygonsByCameraId,
    selectedCameraId,
    setJointViewIndex,
    setRoiPolygonForCamera,
    setSelectedCameraId,
  };
}

function clampReferenceCameraId(cameraId: number) {
  const nextCameraId = clampViewIndex(cameraId);
  return REFERENCE_CAMERA_IDS.includes(nextCameraId as (typeof REFERENCE_CAMERA_IDS)[number])
    ? nextCameraId
    : REFERENCE_ACTIVE_CAMERA_ID;
}
