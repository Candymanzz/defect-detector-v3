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

export type InspectionProduct = {
  key: string;
  phaseId: number;
  groupId: number;
  cameraIds: number[];
  triggerSequence?: number;
  overallPass?: boolean;
  serverTsMs?: number;
  resultsByCameraId: Record<number, InspectResultPayload>;
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

export type InspectionStats = {
  total: number;
  passed: number;
  failed: number;
  groups?: InspectionStatsGroup[];
  referenceFrameId?: string;
  referenceSetAtMs?: number;
  inspectionStartedAtMs?: number;
  inspectionStoppedAtMs?: number;
};

export type InspectionStatsGroup = {
  id: string;
  label: string;
  cameraIds: number[];
  total: number;
  passed: number;
  failed: number;
};

export type ModalInspectionSnapshot = SelectedCamera & {
  productKey?: string;
  phaseId?: number;
  groupId?: number;
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
