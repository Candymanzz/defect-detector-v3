/** Score/threshold live in 0…1; UI shows the same scale as percents. */
export function formatScorePercent(score?: number | null): string {
  if (score === undefined || score === null || !Number.isFinite(score)) {
    return "—";
  }
  return `${(score * 100).toFixed(2)}%`;
}
