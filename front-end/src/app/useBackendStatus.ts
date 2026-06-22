import { useEffect, useRef, useState } from "react";
import { orchestratorApi } from "../shared/api";

const BACKEND_STATUS_RETRY_MS = 1000;

type BackendStatus = {
  state: "loading" | "ready" | "error";
  text: string;
};

export function useBackendStatus() {
  const [status, setStatus] = useState<BackendStatus>({
    state: "loading",
    text: "Проверка...",
  });
  const observedUnavailableRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    let retryTimerId: number | null = null;

    const checkBackend = async () => {
      try {
        const health = (await orchestratorApi.health()).trim();
        if (health.toLowerCase() !== "ok") {
          throw new Error(`Unexpected health status: ${health || "empty"}`);
        }
        if (cancelled) {
          return;
        }

        setStatus({
          state: "ready",
          text: "Подключено",
        });

        if (observedUnavailableRef.current) {
          window.location.reload();
        }
      } catch {
        if (cancelled) {
          return;
        }

        observedUnavailableRef.current = true;
        setStatus({
          state: "error",
          text: "Нет подключения",
        });
        retryTimerId = window.setTimeout(checkBackend, BACKEND_STATUS_RETRY_MS);
      }
    };

    void checkBackend();

    return () => {
      cancelled = true;
      if (retryTimerId !== null) {
        window.clearTimeout(retryTimerId);
      }
    };
  }, []);

  return status;
}
