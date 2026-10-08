export function formatAnomalyPercent(score?: number | null) {
  if (score == null || !Number.isFinite(score)) {
    return "—";
  }

  return `${(score * 100).toFixed(2)}%`;
}
