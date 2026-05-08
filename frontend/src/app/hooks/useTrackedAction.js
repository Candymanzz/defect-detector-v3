import { useCallback } from "react";

const buildErrorPayload = (error) => ({
  error: error?.message || String(error)
});

export function useTrackedAction({ logDesktop, setBusy }) {
  return useCallback(
    async ({
      event,
      payload = {},
      run,
      buildFinishedPayload,
      alertOnError = true,
      manageBusy = true
    }) => {
      const startedAt = performance.now();
      if (manageBusy) setBusy(true);
      logDesktop("info", `${event}.started`, payload);

      try {
        const result = await run({ startedAt });
        const finishedExtras =
          typeof buildFinishedPayload === "function" ? buildFinishedPayload(result) : null;
        logDesktop("info", `${event}.finished`, {
          ...payload,
          ...(finishedExtras || {}),
          duration_ms: Math.round(performance.now() - startedAt)
        });
        return { ok: true, value: result };
      } catch (error) {
        logDesktop("error", `${event}.failed`, {
          ...payload,
          duration_ms: Math.round(performance.now() - startedAt),
          ...buildErrorPayload(error)
        });
        if (alertOnError && error?.message) window.alert(error.message);
        return { ok: false, error };
      } finally {
        if (manageBusy) setBusy(false);
      }
    },
    [logDesktop, setBusy]
  );
}
