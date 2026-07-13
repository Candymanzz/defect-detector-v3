import { useCallback, useState } from "react";
import { getReferenceImage } from "../../shared/referenceImages";
import type { FpZoneNorm } from "../../shared/ws";

export function useReferenceFpZones(cameraGroups: number[][], activeGroupIndex: number, useStoredZones = true) {
  const [editedZonesByGroupKey, setEditedZonesByGroupKey] = useState<Record<string, FpZoneNorm[]>>({});
  const activeCameraIds = cameraGroups[activeGroupIndex] ?? [];
  const activeGroupKey = createGroupKey(activeCameraIds);
  const fpZones = editedZonesByGroupKey[activeGroupKey] ?? (useStoredZones ? copyZones(getStoredZones(activeCameraIds)) : []);

  const setFpZones = useCallback((zones: FpZoneNorm[]) => {
    setEditedZonesByGroupKey((previous) => ({
      ...previous,
      [activeGroupKey]: copyZones(zones),
    }));
  }, [activeGroupKey]);

  const getFpZonesForCameraIds = (cameraIds: number[]) => {
    const groupKey = createGroupKey(cameraIds);
    return copyZones(editedZonesByGroupKey[groupKey] ?? (useStoredZones ? getStoredZones(cameraIds) : [])).filter(
      (zone) => zone.points_norm_heatmap.length >= 3,
    );
  };

  const hasValidFpZonesForCameraIds = () => true;

  const resetEditedFpZonesForCameraIds = (cameraIds: number[]) => {
    const groupKey = createGroupKey(cameraIds);
    setEditedZonesByGroupKey((previous) =>
      Object.fromEntries(Object.entries(previous).filter(([key]) => key !== groupKey)),
    );
  };

  return { fpZones, setFpZones, getFpZonesForCameraIds, hasValidFpZonesForCameraIds, resetEditedFpZonesForCameraIds };
}

function getStoredZones(cameraIds: number[]) {
  for (const cameraId of cameraIds) {
    const zones = getReferenceImage(cameraId)?.fpZones;
    if (zones) return zones;
  }
  return [];
}

function copyZones(zones: FpZoneNorm[]) {
  return zones.map((zone) => ({
    ...zone,
    points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ x: point.x, y: point.y })),
  }));
}

function createGroupKey(cameraIds: number[]) {
  return cameraIds.join(",");
}
