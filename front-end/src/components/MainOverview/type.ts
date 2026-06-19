export type BackendStatus = {
  state: "loading" | "ready" | "error";
  text: string;
};

export type SelectedCamera = {
  cameraId: number;
  objectName: string;
};

export type CameraCardData = {
  cameraId: number;
  objectName: string;
  imageUrl?: string;
};

export type CameraImageUrlsById = Record<number, string>;

export type MainOverviewData = {
  backendStatus: BackendStatus;
  cameraIds: number[];
};

export type InspectionControlState = {
  isEnabled: boolean;
  state: "idle" | "starting" | "stopping" | "error";
  message: string;
};

export type InspectionHistoryItem = {
  frameId: string;
  result: "pass" | "fail";
  inspectResult: InspectResultPayload;
};

export type ModalInspectionSnapshot = SelectedCamera & {
  initialFrameId?: string;
  inspectResult?: InspectResultPayload;
  cameraImageUrl?: string;
  heatmapUrl?: string;
  referenceImageUrl?: string;
  referenceRoiPoints?: InterestPointNorm[];
  inspectionItems: InspectionHistoryItem[];
};
import type { InspectResultPayload, InterestPointNorm } from "../../shared/ws";
