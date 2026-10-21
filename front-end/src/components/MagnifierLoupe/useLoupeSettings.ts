import { useCallback, useState } from "react";

const STORAGE_KEY = "defect-detector.loupe";
export const LOUPE_MIN_ZOOM = 2;
export const LOUPE_MAX_ZOOM = 16;
const DEFAULT_ZOOM = 5;

export type LoupeSettings = {
  enabled: boolean;
  zoom: number;
  setEnabled: (enabled: boolean) => void;
  setZoom: (zoom: number) => void;
};

type Stored = { enabled: boolean; zoom: number };

function clampZoom(value: number) {
  if (!Number.isFinite(value)) return DEFAULT_ZOOM;
  return Math.min(LOUPE_MAX_ZOOM, Math.max(LOUPE_MIN_ZOOM, Math.round(value)));
}

function read(): Stored {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<Stored>;
      return { enabled: parsed.enabled !== false, zoom: clampZoom(Number(parsed.zoom ?? DEFAULT_ZOOM)) };
    }
  } catch {
    // storage unavailable or corrupted — fall back to defaults
  }
  return { enabled: true, zoom: DEFAULT_ZOOM };
}

function write(value: Stored) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  } catch {
    // ignore
  }
}

/** Настройки лупы (вкл/выкл и сила зума), общие для всех редакторов контуров. */
export function useLoupeSettings(): LoupeSettings {
  const [state, setState] = useState<Stored>(read);

  const setEnabled = useCallback((enabled: boolean) => {
    setState((current) => {
      const next = { ...current, enabled };
      write(next);
      return next;
    });
  }, []);

  const setZoom = useCallback((zoom: number) => {
    setState((current) => {
      const next = { ...current, zoom: clampZoom(zoom) };
      write(next);
      return next;
    });
  }, []);

  return { enabled: state.enabled, zoom: state.zoom, setEnabled, setZoom };
}
