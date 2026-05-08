import { formatDuration } from "../../../shared/lib/lib";

export const buildInspectionLogRow = (data, elapsedMs) => ({
  ts: new Date().toLocaleTimeString(),
  result: data.status,
  score: Number(data.anomaly_score).toFixed(3),
  recheckedZonesCount: Number(data.rechecked_zones_count || 0),
  recheckAdjustment: Number(data.recheck_adjustment || 0).toFixed(3),
  duration: formatDuration(elapsedMs)
});
