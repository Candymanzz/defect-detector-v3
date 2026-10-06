import { useCallback, useMemo, useState } from "react";
import { getReferenceImage } from "../../shared/referenceImages";
import type { InspectionBucket } from "../../shared/inspectionBuckets";
import type { FpZoneNorm } from "../../shared/ws";

export function useReferenceFpZones(cameraGroups: InspectionBucket[], activeGroupIndex: number, useStoredZones = true) {
  const [editedZonesByCameraId, setEditedZonesByCameraId] = useState<Record<string, FpZoneNorm[]>>({});
  const activeGroup = cameraGroups[activeGroupIndex];
  const activeCameraIds = useMemo(() => activeGroup?.cameraIds ?? [], [activeGroup]);
  const activeGroupId = activeGroup?.groupId ?? 0;
  const activePhaseId = activeGroup?.phaseId ?? 0;
  const fpZonesByCameraId = Object.fromEntries(
    activeCameraIds.map((cameraId) => [
      cameraId,
      editedZonesByCameraId[zoneKey(activeGroupId, cameraId)] ??
        (useStoredZones ? copyZones(getStoredZonesForCamera(cameraId, activePhaseId, activeGroupId)) : []),
    ]),
  ) as Record<number, FpZoneNorm[]>;

  const setFpZonesForCameraId = useCallback((cameraId: number, zones: FpZoneNorm[]) => {
    setEditedZonesByCameraId((previous) => ({
      ...previous,
      [zoneKey(activeGroupId, cameraId)]: copyZones(zones),
    }));
  }, [activeGroupId]);

  const getFpZonesForCameraIds = (cameraIds: number[]) => {
    return cameraIds.flatMap((cameraId) =>
      withCameraId(
        copyZones(
          editedZonesByCameraId[zoneKey(activeGroupId, cameraId)] ??
            (useStoredZones ? getStoredZonesForCamera(cameraId, activePhaseId, activeGroupId) : []),
        ).filter((zone) => zone.points_norm_heatmap.length >= 3),
        cameraId,
      ),
    );
  };

  const resetEditedFpZonesForCameraIds = (cameraIds: number[]) => {
    const cameraIdSet = new Set(cameraIds);
    setEditedZonesByCameraId((previous) =>
      Object.fromEntries(
        Object.entries(previous).filter(([key]) => {
          const cameraId = Number(key.split(":")[1]);
          return !key.startsWith(`${activeGroupId}:`) || !cameraIdSet.has(cameraId);
        }),
      ),
    );
  };

  return {
    fpZonesByCameraId,
    setFpZonesForCameraId,
    getFpZonesForCameraIds,
    resetEditedFpZonesForCameraIds,
  };
}

function getStoredZonesForCamera(cameraId: number, phaseId: number, groupId: number) {
  return getReferenceImage(cameraId, phaseId, groupId)?.fpZones?.filter(
    (zone) => zone.camera_id === undefined || zone.camera_id === cameraId,
  ) ?? [];
}

function zoneKey(groupId: number, cameraId: number) {
  return `${groupId}:${cameraId}`;
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
