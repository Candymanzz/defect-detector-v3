import { useEffect, useMemo, useState } from "react";
import { DEFAULT_STATE } from "../../app/initialState";
import { getBucketSideArea, getPhysicalAreaStats } from "../lib/geometry";

const getInitialBucketGeometry = () => ({ ...DEFAULT_STATE.bucketGeometry });
const getInitialProjectionParams = () => ({ ...DEFAULT_STATE.projectionParams });

const safeNumber = (value, fallback) =>
  Number.isFinite(value) ? Number(value.toFixed(1)) : fallback;

export function useBucketProjection({ itemPolygon, referenceBox, roiPolygon }) {
  const [bucketGeometry, setBucketGeometry] = useState(getInitialBucketGeometry);
  const [projectionParams, setProjectionParams] = useState(getInitialProjectionParams);

  const bucketSideArea = useMemo(() => getBucketSideArea(bucketGeometry), [bucketGeometry]);

  const physicalAreaStats = useMemo(
    () =>
      getPhysicalAreaStats({
        roiPolygon,
        itemPolygon,
        referenceBox,
        bucketGeometry,
        projectionParams
      }),
    [roiPolygon, itemPolygon, referenceBox, bucketGeometry, projectionParams]
  );

  useEffect(() => {
    if (itemPolygon.length < 3 || !referenceBox.width || !referenceBox.height) return;

    const xs = itemPolygon.map((p) => p.x * referenceBox.width);
    const ys = itemPolygon.map((p) => p.y * referenceBox.height);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);

    setProjectionParams((prev) => ({
      ...prev,
      pixelTopY: safeNumber(minY, prev.pixelTopY),
      pixelBottomY: safeNumber(maxY, prev.pixelBottomY),
      pixelCenterX: safeNumber((minX + maxX) / 2, prev.pixelCenterX)
    }));
  }, [itemPolygon, referenceBox.width, referenceBox.height]);

  return {
    bucketGeometry,
    setBucketGeometry,
    projectionParams,
    setProjectionParams,
    bucketSideArea,
    physicalAreaStats
  };
}
