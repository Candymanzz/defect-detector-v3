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
  logDesktop,
  productType,
  heatmapImageSize,
  setActiveContourMode,
  setBusy
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
    const startedAt = performance.now();
    setBusy(true);
    logDesktop("info", "fp_zone.create.started", {
      product_type: productType,
      points_count: fpPolygonDraft.length,
      heatmap_w: heatmapImageSize.width,
      heatmap_h: heatmapImageSize.height
    });
    try {
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
      if (res.ok) {
        const zone = await res.json();
        logDesktop("info", "fp_zone.create.finished", {
          product_type: productType,
          zone_id: zone.id,
          points_count: fpPolygonDraft.length,
          duration_ms: Math.round(performance.now() - startedAt)
        });
      }
      if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось сохранить FP-зону"));
      setFpPolygonDraft([]);
      setActiveContourMode(null);
      await loadFpZones();
    } catch (error) {
      logDesktop("error", "fp_zone.create.failed", {
        product_type: productType,
        points_count: fpPolygonDraft.length,
        duration_ms: Math.round(performance.now() - startedAt),
        error: error?.message || String(error)
      });
      window.alert(error.message);
    } finally {
      setBusy(false);
    }
  }, [
    apiFetch,
    fpPolygonDraft,
    heatmapImageSize,
    loadFpZones,
    logDesktop,
    productType,
    setActiveContourMode,
    setBusy
  ]);

  const deleteFpZone = useCallback(
    async (zoneId) => {
      const startedAt = performance.now();
      setBusy(true);
      logDesktop("info", "fp_zone.delete.started", {
        product_type: productType,
        zone_id: zoneId
      });
      try {
        const res = await apiFetch(
          `/fp-zones/${encodeURIComponent(zoneId)}`,
          { method: "DELETE" },
          { event: "fp_zone.delete" }
        );
        if (res.ok) {
          logDesktop("info", "fp_zone.delete.finished", {
            product_type: productType,
            zone_id: zoneId,
            duration_ms: Math.round(performance.now() - startedAt)
          });
        }
        if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось удалить FP-зону"));
        await loadFpZones();
      } catch (error) {
        logDesktop("error", "fp_zone.delete.failed", {
          product_type: productType,
          zone_id: zoneId,
          duration_ms: Math.round(performance.now() - startedAt),
          error: error?.message || String(error)
        });
        window.alert(error.message);
      } finally {
        setBusy(false);
      }
    },
    [apiFetch, loadFpZones, logDesktop, productType, setBusy]
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
