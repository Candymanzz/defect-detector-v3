const ANALYSIS_SETTINGS_CHANGED_EVENT = "analysis-settings-changed";

export function notifyAnalysisSettingsChanged(cameraId: number | null) {
  window.dispatchEvent(new CustomEvent(ANALYSIS_SETTINGS_CHANGED_EVENT, { detail: { cameraId } }));
}

export function subscribeAnalysisSettingsChanged(listener: (cameraId: number | null | undefined) => void) {
  const handleChange = (event: Event) => {
    listener((event as CustomEvent<{ cameraId?: number | null }>).detail?.cameraId);
  };
  window.addEventListener(ANALYSIS_SETTINGS_CHANGED_EVENT, handleChange);
  return () => window.removeEventListener(ANALYSIS_SETTINGS_CHANGED_EVENT, handleChange);
}
