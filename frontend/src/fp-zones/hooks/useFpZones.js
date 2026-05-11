import { useCallback, useState } from "react";
import { DEFAULT_STATE } from "../../app/initialState";
import { getErrorMessage } from "../../shared/lib/lib";

const getRecheckStatsFromInspection = (data) => ({
  count: Number(data.rechecked_zones_count || 0),
  adjustment: Number(data.recheck_adjustment || 0),
  rawScore: Number(data.raw_anomaly_score || data.anomaly_score || 0)
});

export function useFpZones({
  apiFetch,
  productType,
  heatmapImageSize,
  setActiveContourMode,
  runTracked
}) {
  const [fpPolygonDraft, setFpPolygonDraft] = useState([]);
  const [fpZones, setFpZones] = useState([]);
  const [lastRecheckedZoneIds, setLastRecheckedZoneIds] = useState([]);
  const [recheckStats, setRecheckStats] = useState(() => ({ ...DEFAULT_STATE.recheckStats }));

  const addFpPoint = useCallback((point) => {
    setFpPolygonDraft((prev) => [...prev, point]);
  }, []);

  const clearFpDraft = useCallback(() => {
    setFpPolygonDraft([]);
  }, []);

  const clearFpZones = useCallback(() => {
    setFpZones([]);
  }, []);

  const applyInspectionRecheck = useCallback((data) => {
    setLastRecheckedZoneIds(data.rechecked_zone_ids || []);
    setRecheckStats(getRecheckStatsFromInspection(data));
  }, []);

  const resetInspectionRecheck = useCallback(() => {
    setFpPolygonDraft([]);
    setLastRecheckedZoneIds([]);
    setRecheckStats({ ...DEFAULT_STATE.recheckStats });
  }, []);

  const loadFpZones = useCallback(async () => {
    try {
      const res = await apiFetch(
        `/fp-zones/${encodeURIComponent(productType)}`,
        {},
        { event: "fp_zones.load", logHttpError: false }
      );
      if (!res.ok) {
        setFpZones([]);
        return;
      }
      const data = await res.json();
      const zones = Array.isArray(data.zones) ? data.zones : [];
      setFpZones(zones);
    } catch {
      setFpZones([]);
    }
  }, [apiFetch, productType]);

  const saveFpZone = useCallback(async () => {
    if (fpPolygonDraft.length < 3 || !heatmapImageSize.width || !heatmapImageSize.height) return;

    const result = await runTracked({
      event: "fp_zone.create",
      payload: {
        product_type: productType,
        points_count: fpPolygonDraft.length,
        heatmap_w: heatmapImageSize.width,
        heatmap_h: heatmapImageSize.height
      },
      run: async () => {
        const res = await apiFetch(
          "/fp-zones",
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              product_type: productType,
              points: fpPolygonDraft,
              heatmap_w: heatmapImageSize.width,
              heatmap_h: heatmapImageSize.height
            })
          },
          { event: "fp_zone.create" }
        );
        if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось сохранить FP-зону"));
        return await res.json();
      },
      buildFinishedPayload: (zone) => ({
        zone_id: zone.id
      })
    });

    if (result.ok) {
      setFpPolygonDraft([]);
      setActiveContourMode(null);
      await loadFpZones();
    }
  }, [
    apiFetch,
    fpPolygonDraft,
    heatmapImageSize,
    loadFpZones,
    productType,
    runTracked,
    setActiveContourMode
  ]);

  const deleteFpZone = useCallback(
    async (zoneId) => {
      const result = await runTracked({
        event: "fp_zone.delete",
        payload: {
          product_type: productType,
          zone_id: zoneId
        },
        run: async () => {
          const res = await apiFetch(
            `/fp-zones/${encodeURIComponent(zoneId)}`,
            { method: "DELETE" },
            { event: "fp_zone.delete" }
          );
          if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось удалить FP-зону"));
        }
      });

      if (result.ok) {
        await loadFpZones();
      }
    },
    [apiFetch, loadFpZones, productType, runTracked]
  );

  return {
    fpPolygonDraft,
    fpZones,
    lastRecheckedZoneIds,
    recheckStats,
    addFpPoint,
    clearFpDraft,
    clearFpZones,
    applyInspectionRecheck,
    resetInspectionRecheck,
    loadFpZones,
    saveFpZone,
    deleteFpZone
  };
}
