import type { GeometryInspectResponse } from "../../shared/api";

export function geometrySkipReason(geometry: GeometryInspectResponse): string | null {
  if (!["SKIP", "SKIPPED"].includes(String(geometry.status ?? "").trim().toUpperCase())) return null;
  const reason = String(geometry.skipReason ?? geometry.error ?? "");
  if (reason.includes("no joint ROI")) return "Шов не проверен: в эталоне не задана область шва.";
  if (reason.includes("disabled")) return "Проверка геометрии выключена для этой камеры.";
  if (reason.includes("no reference")) return "Геометрия не проверена: отсутствует эталон.";
  return "Геометрия не проверена для этого кадра.";
}
