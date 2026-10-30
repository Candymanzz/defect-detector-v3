/** Score/threshold live in 0…1; UI shows the same scale as percents. */
export function formatScorePercent(score?: number | null): string {
  if (score === undefined || score === null || !Number.isFinite(score)) {
    return "—";
  }
  if (score > 0 && score * 100 < 0.005) return "<0.01%";
  return `${(score * 100).toFixed(2)}%`;
}
