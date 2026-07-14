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
  cameraIds: number[];
};

export type InspectionControlState = {
  isEnabled: boolean;
  state: "idle" | "starting" | "stopping" | "error";
  message: string;
};

export type InspectionHistoryItem = {
  frameId: string;
  inspectionId: string;
  result: "pass" | "fail" | "capture";
  inspectResult: InspectResultPayload;
};

export type ModalInspectionSnapshot = SelectedCamera & {
  initialFrameId?: string;
  inspectResult?: InspectResultPayload;
  cameraImageUrl?: string;
  heatmapUrl?: string;
  referenceImageUrl?: string;
  referenceRoiPoints?: InterestPointNorm[];
  referenceJointRoiPoints?: InterestPointNorm[];
  referenceFpZones?: FpZoneNorm[];
  inspectionItems: InspectionHistoryItem[];
};
import type { FpZoneNorm, InspectResultPayload, InterestPointNorm } from "../../shared/ws";
