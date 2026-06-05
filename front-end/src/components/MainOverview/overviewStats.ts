import type { InspectResultPayload } from "../../shared/ws";
import type { BackendStatus } from "./type";
import type { OverviewStatItem } from "./OverviewStat";

type CreateOverviewStatsParams = {
  backendStatus: BackendStatus;
  cameraCount: number;
  errorCameraCount: number;
  lastInspectResult?: InspectResultPayload;
  onlineCameraCount: number;
};

export function createOverviewStats({
  backendStatus,
  cameraCount,
  errorCameraCount,
  lastInspectResult,
  onlineCameraCount,
}: CreateOverviewStatsParams): OverviewStatItem[] {
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
      value: errorCameraCount,
      caption: backendStatus.state === "ready" ? "Backend online" : backendStatus.text,
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
