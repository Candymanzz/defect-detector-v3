import { useState } from "react";
import type { InterestPointNorm } from "../../shared/ws";
import {
  REFERENCE_ACTIVE_CAMERA_ID,
  REFERENCE_CAMERA_IDS,
  REFERENCE_JOINT_ROI_CAMERA_ID,
  REFERENCE_REQUIRED_CAMERA_IDS,
} from "./referenceConstants";
import { clampViewIndex, isValidRoiPolygon } from "./referenceRoi";

export type ReferenceRoiEditMode = "interest" | "joint";

export function useReferenceRoi(initialJointViewIndex: number | null = null) {
  const initialCameraId = clampReferenceCameraId(initialJointViewIndex ?? REFERENCE_ACTIVE_CAMERA_ID);
  const jointViewIndex = REFERENCE_JOINT_ROI_CAMERA_ID;
  const [roiPolygonsByCameraId, setRoiPolygonsByCameraId] = useState<Record<number, InterestPointNorm[]>>({});
  const [jointRoiPolygon, setJointRoiPolygon] = useState<InterestPointNorm[]>([]);
  const [selectedCameraId, setSelectedCameraIdState] = useState(initialCameraId);
  const [selectedRoiMode, setSelectedRoiMode] = useState<ReferenceRoiEditMode>("interest");
  const hasSelectedCameraRoi = isValidRoiPolygon(roiPolygonsByCameraId[selectedCameraId]);
  const hasRequiredCameraRois = REFERENCE_REQUIRED_CAMERA_IDS.every((cameraId) =>
    isValidRoiPolygon(roiPolygonsByCameraId[cameraId]),
  );
  const hasRequiredJointRoi = isValidRoiPolygon(jointRoiPolygon);

  const setRoiPolygonForCamera = (cameraId: number, points: InterestPointNorm[]) => {
    setRoiPolygonsByCameraId((prev) => ({
      ...prev,
      [cameraId]: points,
    }));
  };

  const setSelectedCameraId = (cameraId: number) => {
    const nextCameraId = clampReferenceCameraId(cameraId);
    setSelectedCameraIdState(nextCameraId);
    setSelectedRoiMode("interest");
  };

  const selectJointRoi = () => {
    setSelectedCameraIdState(REFERENCE_JOINT_ROI_CAMERA_ID);
    setSelectedRoiMode("joint");
  };

  return {
    jointViewIndex,
    hasSelectedCameraRoi,
    hasRequiredCameraRois,
    hasRequiredJointRoi,
    jointRoiPolygon,
    roiPolygonsByCameraId,
    selectedCameraId,
    selectedRoiMode,
    selectJointRoi,
    setJointRoiPolygon,
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
