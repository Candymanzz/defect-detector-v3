import type { InspectResultPayload } from "../../shared/ws";
import type { BackendStatus } from "./type";
import type { OverviewStatItem } from "./OverviewStat";

type CreateOverviewStatsParams = {
  backendStatus: BackendStatus;
  cameraCount: number;
  offlineCameraCount: number;
  lastInspectResult?: InspectResultPayload;
  onlineCameraCount: number;
  waitingCameraCount: number;
};

export function createOverviewStats({
  backendStatus,
  cameraCount,
  offlineCameraCount,
  lastInspectResult,
  onlineCameraCount,
  waitingCameraCount,
}: CreateOverviewStatsParams): OverviewStatItem[] {
  const backendErrorCount = backendStatus.state === "error" ? 1 : 0;
  const totalErrorCount = offlineCameraCount + backendErrorCount;

  return [
    {
      id: "cameras",
      label: "Камеры",
      value: cameraCount,
      caption: "Всего камер",
      tone: "green",
    },
    {
      id: "online",
      label: "Онлайн",
      value: onlineCameraCount,
      caption: "Камер в сети",
      tone: "cyan",
    },
    {
      id: "errors",
      label: "Ошибки",
      value: totalErrorCount,
      caption:
        backendStatus.state === "error"
          ? `Backend offline; cameras offline: ${offlineCameraCount}`
          : backendStatus.state === "ready"
          ? waitingCameraCount > 0
            ? `Waiting for frames: ${waitingCameraCount}`
            : "No stale cameras"
          : backendStatus.text,
      tone: "amber",
    },
    {
      id: "last-inspection",
      label: "Последняя проверка",
      value: lastInspectResult ? formatInspectTime(lastInspectResult.server_ts_ms) : "-",
      caption: lastInspectResult ? `Camera ${lastInspectResult.camera_id}` : "Нет данных",
      tone: "violet",
    },
  ];
}

function formatInspectTime(serverTsMs: number) {
  if (!Number.isFinite(serverTsMs) || serverTsMs <= 0) {
    return "-";
  }

  return new Date(serverTsMs).toLocaleTimeString();
}
