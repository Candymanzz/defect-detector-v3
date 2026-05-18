export type UiCameraList = {
  cameras: number[];
};

export type UiLatestSnapshot = {
  cameraId: number;
  frameId: number;
  productType: string;
  detectorId: string;
  shmName: string;
  updatedAtMs: number;
  hasCurrent: boolean;
  hasHeatmap: boolean;
  capture: {
    width: number;
    height: number;
  };
  currentJpeg: {
    width: number;
    height: number;
    path: string;
  };
  heatmapU8: {
    width: number;
    height: number;
    path: string;
  };
};

export type GeometryLatestSnapshot = {
  cameraId: number;
  frameId: number;
  updatedAtMs: number;
  latestJsonPath: string;
  geometry: Record<string, unknown>;
};

export type GeometryRuntimeConfig = {
  runtimeOverrides: Record<string, unknown>;
  effectiveForNextGeometryInspect: Record<string, unknown>;
};

export type ApiOk = {
  ok: true;
};
