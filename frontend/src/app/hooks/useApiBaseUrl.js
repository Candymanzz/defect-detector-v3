import { useCallback, useEffect, useMemo, useState } from "react";
import { createAppClient } from "../appClient";
import { getInitialApiBaseUrl } from "../initialState";
import { getDesktopBridge, normalizeApiBaseUrl } from "../../shared/lib/lib";

export function useApiBaseUrl() {
  const [apiBaseUrl, setApiBaseUrl] = useState(getInitialApiBaseUrl);
  const [apiBaseUrlDraft, setApiBaseUrlDraft] = useState(getInitialApiBaseUrl);
  const [configReady, setConfigReady] = useState(() => !getDesktopBridge()?.getConfig);

  const { apiFetch, logDesktop } = useMemo(() => createAppClient(apiBaseUrl), [apiBaseUrl]);

  useEffect(() => {
    let isMounted = true;
    const desktopBridge = getDesktopBridge();
    if (!desktopBridge?.getConfig) {
      setConfigReady(true);
      return () => {
        isMounted = false;
      };
    }

    desktopBridge
      .getConfig()
      .then((config) => {
        if (!isMounted) return;
        const desktopApiBaseUrl = normalizeApiBaseUrl(config?.apiBaseUrl);
        if (desktopApiBaseUrl || !import.meta.env.DEV) {
          setApiBaseUrl(desktopApiBaseUrl);
          setApiBaseUrlDraft(desktopApiBaseUrl);
        }
      })
      .finally(() => {
        if (isMounted) setConfigReady(true);
      });

    return () => {
      isMounted = false;
    };
  }, []);

  const saveApiBaseUrl = useCallback(async () => {
    const normalizedBaseUrl = normalizeApiBaseUrl(apiBaseUrlDraft);
    if (!normalizedBaseUrl) {
      window.alert("Backend API URL is not configured");
      return;
    }

    setApiBaseUrl(normalizedBaseUrl);
    setApiBaseUrlDraft(normalizedBaseUrl);

    const desktopBridge = getDesktopBridge();
    if (desktopBridge?.setConfig) {
      try {
        const config = await desktopBridge.setConfig({ apiBaseUrl: normalizedBaseUrl });
        const savedApiBaseUrl = normalizeApiBaseUrl(config?.apiBaseUrl);
        if (savedApiBaseUrl) {
          setApiBaseUrl(savedApiBaseUrl);
          setApiBaseUrlDraft(savedApiBaseUrl);
        }
      } catch {
        window.alert("Could not save desktop config");
      }
    }

    logDesktop("info", "backend_url.selected", {
      apiBaseUrl: normalizedBaseUrl
    });
  }, [apiBaseUrlDraft, logDesktop]);

  return {
    apiBaseUrl,
    apiBaseUrlDraft,
    setApiBaseUrlDraft,
    configReady,
    saveApiBaseUrl,
    apiFetch,
    logDesktop
  };
}
