import { useCallback, useEffect, useMemo, useState } from "react";
import { DEFAULT_STATE } from "../../app/initialState";
import { getErrorMessage, toDataUrl } from "../../shared/lib/lib";
import { getNormalizedPointInImageBox } from "../lib/imageBox";
import { getPolygonArea } from "../lib/geometry";

const getInitialRoi = () => ({ ...DEFAULT_STATE.roi });

export function useReferenceWorkspace({
  apiFetch,
  productType,
  apiBaseUrl,
  configReady,
  capturedFile,
  cameraSource,
  referenceBox,
  activeContourMode,
  setActiveContourMode,
  referencePreview,
  setReferencePreview,
  roiPolygon,
  setRoiPolygon,
  itemPolygon,
  setItemPolygon,
  setImages,
  runTracked,
  loadFpZones,
  clearFpDraft,
  clearFpZones
}) {
  const [roi, setRoi] = useState(getInitialRoi);

  const roiArea = useMemo(() => getPolygonArea(roiPolygon), [roiPolygon]);
  const itemArea = useMemo(() => getPolygonArea(itemPolygon), [itemPolygon]);
  const roiCoveragePercent = useMemo(() => {
    if (itemArea <= 0) return 0;
    return (roiArea / itemArea) * 100;
  }, [roiArea, itemArea]);

  const clearRoiPolygon = useCallback(() => setRoiPolygon([]), [setRoiPolygon]);
  const clearItemPolygon = useCallback(() => setItemPolygon([]), [setItemPolygon]);

  const addPolygonPoint = useCallback(
    (event) => {
      if (!activeContourMode || !referencePreview) return;
      const point = getNormalizedPointInImageBox({
        clientX: event.clientX,
        clientY: event.clientY,
        containerRect: event.currentTarget.getBoundingClientRect(),
        imageBox: referenceBox
      });
      if (!point) return;

      if (activeContourMode === "roi") {
        setRoiPolygon((prev) => [...prev, point]);
      } else if (activeContourMode === "item") {
        setItemPolygon((prev) => [...prev, point]);
      }
    },
    [activeContourMode, referenceBox, referencePreview, setItemPolygon, setRoiPolygon]
  );

  const uploadReference = useCallback(async () => {
    if (!capturedFile) return;

    await runTracked({
      event: "reference.upload",
      payload: {
        product_type: productType,
        filename: capturedFile.name
      },
      run: async () => {
        const formData = new FormData();
        formData.append("product_type", productType);
        formData.append("file", capturedFile);

        const res = await apiFetch(
          "/upload-ref",
          { method: "POST", body: formData },
          { event: "reference.upload" }
        );
        if (!res.ok) throw new Error("Не удалось загрузить эталон");
        setReferencePreview(URL.createObjectURL(capturedFile));
      }
    });
  }, [apiFetch, capturedFile, productType, runTracked, setReferencePreview]);

  const uploadReferenceFromCamera = useCallback(async () => {
    await runTracked({
      event: "reference.upload_from_camera",
      payload: {
        product_type: productType,
        camera_source: cameraSource
      },
      run: async () => {
        const formData = new FormData();
        formData.append("product_type", productType);
        formData.append("camera_server_url", cameraSource);

        const res = await apiFetch(
          "/upload-ref-from-camera",
          { method: "POST", body: formData },
          { event: "reference.upload_from_camera" }
        );
        if (!res.ok) {
          throw new Error(await getErrorMessage(res, "Не удалось задать эталон с камеры"));
        }

        const data = await res.json();
        const referenceDataUrl = toDataUrl(data.reference_b64);
        setReferencePreview(referenceDataUrl);
        setImages((prev) => ({ ...prev, original: referenceDataUrl }));
        return data;
      },
      buildFinishedPayload: (data) => ({
        camera_source: data.camera_source || cameraSource,
        camera_duration_ms: Number(data.camera_duration_ms || 0)
      })
    });
  }, [apiFetch, cameraSource, productType, runTracked, setImages, setReferencePreview]);

  const saveRoi = useCallback(async () => {
    await runTracked({
      event: "roi_rect.save",
      payload: { product_type: productType, roi },
      run: async () => {
        const formData = new FormData();
        formData.append("product_type", productType);
        formData.append("x", String(roi.x));
        formData.append("y", String(roi.y));
        formData.append("w", String(roi.w));
        formData.append("h", String(roi.h));

        const res = await apiFetch(
          "/roi",
          { method: "POST", body: formData },
          { event: "roi_rect.save" }
        );
        if (!res.ok) throw new Error(await getErrorMessage(res, "Не удалось сохранить ROI"));
      }
    });
  }, [apiFetch, productType, roi, runTracked]);

  const savePolygonRoi = useCallback(async () => {
    const result = await runTracked({
      event: "roi_polygon.save",
      payload: {
        product_type: productType,
        points_count: roiPolygon.length
      },
      run: async () => {
        const res = await apiFetch(
          "/roi-polygon",
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              product_type: productType,
              points: roiPolygon
            })
          },
          { event: "roi_polygon.save" }
        );
        if (!res.ok) {
          throw new Error(await getErrorMessage(res, "Не удалось сохранить контур ROI"));
        }
      }
    });

    if (result.ok && activeContourMode === "roi") {
      setActiveContourMode(null);
    }
  }, [activeContourMode, apiFetch, productType, roiPolygon, runTracked, setActiveContourMode]);

  const loadReferenceAndPolygon = useCallback(async () => {
    try {
      const [referenceRes, polygonRes] = await Promise.all([
        apiFetch(
          `/reference/${encodeURIComponent(productType)}`,
          {},
          { event: "reference.load", logHttpError: false }
        ),
        apiFetch(
          `/roi-polygon/${encodeURIComponent(productType)}`,
          {},
          { event: "roi_polygon.load", logHttpError: false }
        )
      ]);

      if (referenceRes.ok) {
        const referenceData = await referenceRes.json();
        setReferencePreview(toDataUrl(referenceData.reference_b64));
      } else {
        setReferencePreview("");
        setRoiPolygon([]);
        return;
      }

      if (polygonRes.ok) {
        const polygonData = await polygonRes.json();
        const points = Array.isArray(polygonData.points) ? polygonData.points : [];
        setRoiPolygon(points.map((p) => ({ x: Number(p.x), y: Number(p.y) })));
      } else {
        setRoiPolygon([]);
      }
      await loadFpZones();
      setItemPolygon([]);
      setActiveContourMode(null);
      clearFpDraft();
    } catch {
      setReferencePreview("");
      setRoiPolygon([]);
      setItemPolygon([]);
      setActiveContourMode(null);
      clearFpDraft();
      clearFpZones();
    }
  }, [
    apiFetch,
    clearFpDraft,
    clearFpZones,
    loadFpZones,
    productType,
    setActiveContourMode,
    setItemPolygon,
    setReferencePreview,
    setRoiPolygon
  ]);

  useEffect(() => {
    if (!configReady) return;
    loadReferenceAndPolygon();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productType, apiBaseUrl, configReady]);

  return {
    roi,
    setRoi,
    roiArea,
    itemArea,
    roiCoveragePercent,
    addPolygonPoint,
    clearRoiPolygon,
    clearItemPolygon,
    uploadReference,
    uploadReferenceFromCamera,
    saveRoi,
    savePolygonRoi
  };
}
