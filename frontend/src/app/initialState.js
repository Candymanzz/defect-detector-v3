import { getDesktopBridge, normalizeApiBaseUrl, readLocalStorage } from "../shared/lib/lib";
import { DEV_API_URL, STORAGE_KEYS } from "./constants";

export const DEFAULT_STATE = {
  images: {
    original: "",
    diff: "",
    heatmap: ""
  },
  cameraSource: "http://localhost:8080",
  roi: {
    x: 0.1,
    y: 0.1,
    w: 0.8,
    h: 0.8
  },
  recheckStats: {
    count: 0,
    adjustment: 0,
    rawScore: 0
  },
  bucketGeometry: {
    topRadius: 155,
    bottomRadius: 125,
    height: 280
  },
  projectionParams: {
    pixelTopY: 20,
    pixelBottomY: 180,
    pixelCenterX: 120
  },
  imageSize: {
    width: 0,
    height: 0
  },
  imageBox: {
    left: 0,
    top: 0,
    width: 0,
    height: 0
  }
};

export const getInitialApiBaseUrl = () => {
  if (getDesktopBridge()?.getConfig) {
    return import.meta.env.DEV ? DEV_API_URL : "";
  }

  const envUrl = normalizeApiBaseUrl(import.meta.env.VITE_API_URL);
  const devFallbackUrl = import.meta.env.DEV ? DEV_API_URL : "";
  return envUrl || devFallbackUrl;
};
export const getInitialProductType = () =>
  readLocalStorage(STORAGE_KEYS.productType) || "bucket-default";
export const getInitialThreshold = () => {
  const savedThreshold = Number(readLocalStorage(STORAGE_KEYS.threshold));
  return Number.isFinite(savedThreshold) ? Math.min(1, Math.max(0, savedThreshold)) : 0.25;
};
