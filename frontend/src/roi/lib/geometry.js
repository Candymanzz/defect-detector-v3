import { calculate } from "../../utils/bucketCalculator";

export const getPolygonArea = (points) => {
  if (!Array.isArray(points) || points.length < 3) return 0;

  let sum = 0;
  for (let i = 0; i < points.length; i += 1) {
    const a = points[i];
    const b = points[(i + 1) % points.length];
    sum += a.x * b.y - b.x * a.y;
  }

  return Math.abs(sum) / 2;
};

export const getBucketSideArea = ({ topRadius, bottomRadius, height }) => {
  const topR = Number(topRadius);
  const bottomR = Number(bottomRadius);
  const h = Number(height);

  if (topR <= 0 || bottomR <= 0 || h <= 0) return 0;

  const slantHeight = Math.sqrt((topR - bottomR) ** 2 + h ** 2);
  return Math.PI * (topR + bottomR) * slantHeight;
};

export const getPhysicalAreaStats = ({
  roiPolygon,
  itemPolygon,
  referenceBox,
  bucketGeometry,
  projectionParams
}) => {
  if (!referenceBox.width || !referenceBox.height) {
    return {
      roiMm2: 0,
      itemMm2: 0,
      coveragePercent: 0,
      coverageFromVisibleSidePercent: 0,
      fullSideAreaMm2: 0,
      visibleSideAreaMm2: 0,
      errorPercent: 0
    };
  }

  const toPixelPoints = (pts) =>
    pts.map((p) => ({
      x: p.x * referenceBox.width,
      y: p.y * referenceBox.height
    }));

  const params = {
    realHeight: Number(bucketGeometry.height),
    rTop: Number(bucketGeometry.topRadius),
    rBottom: Number(bucketGeometry.bottomRadius),
    pixelTopY: Number(projectionParams.pixelTopY),
    pixelBottomY: Number(projectionParams.pixelBottomY),
    pixelCenterX: Number(projectionParams.pixelCenterX),
    visibleAngleFactor: 1.0
  };

  try {
    const roiRes = calculate(toPixelPoints(roiPolygon), params);
    const itemRes = calculate(toPixelPoints(itemPolygon), params);
    const coveragePercent = itemRes.areaMm2 > 0 ? (roiRes.areaMm2 / itemRes.areaMm2) * 100 : 0;
    const fullSideAreaMm2 = getBucketSideArea(bucketGeometry);
    const visibleAngleDeg = 160;
    const visibleSideAreaMm2 = fullSideAreaMm2 * (visibleAngleDeg / 360);
    const coverageFromVisibleSidePercent =
      visibleSideAreaMm2 > 0 ? (roiRes.areaMm2 / visibleSideAreaMm2) * 100 : 0;
    return {
      roiMm2: roiRes.areaMm2,
      itemMm2: itemRes.areaMm2,
      coveragePercent,
      coverageFromVisibleSidePercent,
      fullSideAreaMm2,
      visibleSideAreaMm2,
      errorPercent: Math.max(roiRes.errorPercent, itemRes.errorPercent)
    };
  } catch {
    return {
      roiMm2: 0,
      itemMm2: 0,
      coveragePercent: 0,
      coverageFromVisibleSidePercent: 0,
      fullSideAreaMm2: 0,
      visibleSideAreaMm2: 0,
      errorPercent: 0
    };
  }
};
