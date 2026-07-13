import { useCallback, useMemo, useState } from "react";
import { getReferenceImage } from "../../shared/referenceImages";
import type { FpZoneNorm } from "../../shared/ws";

export function useReferenceFpZones(cameraGroups: number[][], activeGroupIndex: number, useStoredZones = true) {
  const [editedZonesByCameraId, setEditedZonesByCameraId] = useState<Record<number, FpZoneNorm[]>>({});
  const activeCameraIds = useMemo(() => cameraGroups[activeGroupIndex] ?? [], [activeGroupIndex, cameraGroups]);
  const fpZonesByCameraId = Object.fromEntries(
    activeCameraIds.map((cameraId) => [
      cameraId,
      editedZonesByCameraId[cameraId] ?? (useStoredZones ? copyZones(getStoredZonesForCamera(cameraId)) : []),
    ]),
  ) as Record<number, FpZoneNorm[]>;
  const fpZones = activeCameraIds.flatMap((cameraId) => withCameraId(fpZonesByCameraId[cameraId] ?? [], cameraId));

  const setFpZones = useCallback((zones: FpZoneNorm[]) => {
    setEditedZonesByCameraId((previous) => ({
      ...previous,
      ...Object.fromEntries(
        activeCameraIds.map((cameraId) => [
          cameraId,
          copyZones(zones).filter((zone) => zone.camera_id === undefined || zone.camera_id === cameraId),
        ]),
      ),
    }));
  }, [activeCameraIds]);

  const setFpZonesForCameraId = useCallback((cameraId: number, zones: FpZoneNorm[]) => {
    setEditedZonesByCameraId((previous) => ({
      ...previous,
      [cameraId]: copyZones(zones),
    }));
  }, []);

  const getFpZonesForCameraIds = (cameraIds: number[]) => {
    return cameraIds.flatMap((cameraId) =>
      withCameraId(
        copyZones(editedZonesByCameraId[cameraId] ?? (useStoredZones ? getStoredZonesForCamera(cameraId) : [])).filter(
          (zone) => zone.points_norm_heatmap.length >= 3,
        ),
        cameraId,
      ),
    );
  };

  const hasValidFpZonesForCameraIds = () => true;

  const resetEditedFpZonesForCameraIds = (cameraIds: number[]) => {
    const cameraIdSet = new Set(cameraIds);
    setEditedZonesByCameraId((previous) =>
      Object.fromEntries(Object.entries(previous).filter(([cameraId]) => !cameraIdSet.has(Number(cameraId)))),
    );
  };

  return {
    fpZones,
    fpZonesByCameraId,
    setFpZones,
    setFpZonesForCameraId,
    getFpZonesForCameraIds,
    hasValidFpZonesForCameraIds,
    resetEditedFpZonesForCameraIds,
  };
}

function getStoredZonesForCamera(cameraId: number) {
  return getReferenceImage(cameraId)?.fpZones?.filter(
    (zone) => zone.camera_id === undefined || zone.camera_id === cameraId,
  ) ?? [];
}

function copyZones(zones: FpZoneNorm[]) {
  return zones.map((zone) => ({
    ...zone,
    points_norm_heatmap: zone.points_norm_heatmap.map((point) => ({ x: point.x, y: point.y })),
  }));
}

function withCameraId(zones: FpZoneNorm[], cameraId: number) {
  return zones.map((zone) => ({
    ...zone,
    camera_id: cameraId,
  }));
}
