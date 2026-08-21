export function logTestAnalysis(event: string, details: Record<string, unknown> = {}) {
  console.info(`[analysis-test] ${event}`, {
    at: new Date().toISOString(),
    ...details,
  });
}
