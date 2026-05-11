import { useCallback, useState } from "react";
import { DEFAULT_STATE } from "../../../app/initialState";
import { getErrorMessage, revokeBlobUrl, toDataUrl } from "../../../shared/lib/lib";
import { buildInspectionLogRow } from "../lib/buildInspectionLogRow";

const getEmptyImages = () => ({ ...DEFAULT_STATE.images });

export function useInspection({
  apiFetch,
  productType,
  threshold,
  cameraSource,
  capturedFile,
  setCapturedFile,
  setImages,
  applyInspectionRecheck,
  resetInspectionRecheck,
  runTracked
}) {
  const [status, setStatus] = useState("ОЖИДАНИЕ");
  const [score, setScore] = useState(0);
  const [logs, setLogs] = useState([]);

  const handlePickImage = useCallback(
    (event) => {
      const file = event.target.files?.[0];
      if (!file) return;
      setCapturedFile(file);
      setImages((prev) => {
        revokeBlobUrl(prev.original);
        return { ...prev, original: URL.createObjectURL(file) };
      });
    },
    [setCapturedFile, setImages]
  );

  const runInspection = useCallback(async () => {
    if (!capturedFile) return;

    await runTracked({
      event: "inspect",
      payload: {
        product_type: productType,
        threshold,
        filename: capturedFile.name
      },
      run: async ({ startedAt }) => {
        const formData = new FormData();
        formData.append("product_type", productType);
        formData.append("threshold", String(threshold));
        formData.append("file", capturedFile);

        const res = await apiFetch(
          "/inspect",
          { method: "POST", body: formData },
          { event: "inspect" }
        );
        if (!res.ok) throw new Error("Ошибка проверки");

        const data = await res.json();
        const elapsedMs = performance.now() - startedAt;
        setStatus(data.status);
        setScore(data.anomaly_score);
        applyInspectionRecheck(data);
        setImages((prev) => ({
          ...prev,
          diff: toDataUrl(data.diff_map_b64),
          heatmap: toDataUrl(data.heatmap_b64)
        }));
        setLogs((prev) => [buildInspectionLogRow(data, elapsedMs), ...prev]);
        return { data };
      },
      buildFinishedPayload: ({ data }) => ({
        status: data.status,
        score: Number(data.anomaly_score),
        threshold: Number(data.threshold || threshold),
        raw_score: Number(data.raw_anomaly_score || data.anomaly_score || 0),
        rechecked_zones_count: Number(data.rechecked_zones_count || 0)
      })
    });
  }, [
    apiFetch,
    applyInspectionRecheck,
    capturedFile,
    productType,
    runTracked,
    setImages,
    threshold
  ]);

  const runInspectionFromCamera = useCallback(async () => {
    await runTracked({
      event: "inspect_from_camera",
      payload: {
        product_type: productType,
        threshold,
        camera_source: cameraSource
      },
      run: async ({ startedAt }) => {
        const formData = new FormData();
        formData.append("product_type", productType);
        formData.append("threshold", String(threshold));
        formData.append("camera_server_url", cameraSource);

        const res = await apiFetch(
          "/inspect-from-camera",
          { method: "POST", body: formData },
          { event: "inspect_from_camera" }
        );
        if (!res.ok) throw new Error(await getErrorMessage(res, "Ошибка проверки с камеры"));

        const data = await res.json();
        const elapsedMs = performance.now() - startedAt;
        setStatus(data.status);
        setScore(data.anomaly_score);
        applyInspectionRecheck(data);
        setImages({
          original: toDataUrl(data.original_image_b64),
          diff: toDataUrl(data.diff_map_b64),
          heatmap: toDataUrl(data.heatmap_b64)
        });
        setLogs((prev) => [buildInspectionLogRow(data, elapsedMs), ...prev]);
        return { data };
      },
      buildFinishedPayload: ({ data }) => ({
        status: data.status,
        score: Number(data.anomaly_score),
        threshold: Number(data.threshold || threshold),
        camera_source: data.camera_source || cameraSource,
        camera_duration_ms: Number(data.camera_duration_ms || 0),
        raw_score: Number(data.raw_anomaly_score || data.anomaly_score || 0),
        rechecked_zones_count: Number(data.rechecked_zones_count || 0)
      })
    });
  }, [
    apiFetch,
    applyInspectionRecheck,
    cameraSource,
    productType,
    runTracked,
    setImages,
    threshold
  ]);

  const resetInspection = useCallback(() => {
    setStatus("ОЖИДАНИЕ");
    setScore(0);
    setCapturedFile(null);
    setImages((prev) => {
      revokeBlobUrl(prev.original);
      return getEmptyImages();
    });
    resetInspectionRecheck();
  }, [resetInspectionRecheck, setCapturedFile, setImages]);

  return {
    status,
    score,
    logs,
    handlePickImage,
    runInspection,
    runInspectionFromCamera,
    resetInspection
  };
}
