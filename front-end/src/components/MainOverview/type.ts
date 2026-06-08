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
  signalState: "waiting" | "online" | "offline";
};

export type CameraImageUrlsById = Record<number, string>;
export type CameraFrameTimesById = Record<number, number>;
