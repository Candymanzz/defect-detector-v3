export class BucketCalculator {
  constructor(params) {
    this.params = BucketCalculator.normalizeParams(params);
  }

  static normalizeParams(params) {
    const realHeight = Number(params.realHeight);
    const rTop = Number(params.rTop);
    const rBottom = Number(params.rBottom);
    const pixelTopY = Number(params.pixelTopY);
    const pixelBottomY = Number(params.pixelBottomY);
    const pixelCenterX = Number(params.pixelCenterX);
    const aspectRatioCorrection = Number(params.aspectRatioCorrection ?? 1.0);
    const distortionK = Number(params.distortionK ?? -0.12);
    const perspectiveFactor = Number(params.perspectiveFactor ?? 0.2);
    const visibleAngleFactor = Number(params.visibleAngleFactor ?? 1.0);
    const topRimCurvePx = Number(params.topRimCurvePx ?? 0);
    const bottomRimCurvePx = Number(params.bottomRimCurvePx ?? 0);
    const frameWidthPx = Number(params.frameWidthPx ?? 0);
    const frameHeightPx = Number(params.frameHeightPx ?? 0);

    if (!Number.isFinite(realHeight) || realHeight <= 0) {
      throw new Error("realHeight must be > 0");
    }
    if (!Number.isFinite(rTop) || rTop <= 0 || !Number.isFinite(rBottom) || rBottom <= 0) {
      throw new Error("rTop/rBottom must be > 0");
    }
    if (!Number.isFinite(pixelTopY) || !Number.isFinite(pixelBottomY) || pixelBottomY === pixelTopY) {
      throw new Error("pixelTopY and pixelBottomY must be finite and different");
    }
    if (!Number.isFinite(pixelCenterX)) {
      throw new Error("pixelCenterX must be finite");
    }
    if (!Number.isFinite(aspectRatioCorrection) || aspectRatioCorrection <= 0) {
      throw new Error("aspectRatioCorrection must be > 0");
    }
    if (!Number.isFinite(distortionK)) {
      throw new Error("distortionK must be finite");
    }
    if (!Number.isFinite(perspectiveFactor)) {
      throw new Error("perspectiveFactor must be finite");
    }
    if (!Number.isFinite(visibleAngleFactor) || visibleAngleFactor <= 0) {
      throw new Error("visibleAngleFactor must be > 0");
    }

    return {
      realHeight,
      rTop,
      rBottom,
      pixelTopY,
      pixelBottomY,
      pixelCenterX,
      pixelHeight: Math.abs(pixelBottomY - pixelTopY),
      aspectRatioCorrection,
      distortionK,
      perspectiveFactor,
      visibleAngleFactor,
      topRimCurvePx,
      bottomRimCurvePx,
      frameWidthPx,
      frameHeightPx
    };
  }

  static suggestCenterX(points) {
    if (!Array.isArray(points) || points.length < 3) return null;

    const binsCount = 12;
    const ys = points.map((p) => p.y);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const spanY = Math.max(1e-6, maxY - minY);
    const bins = Array.from({ length: binsCount }, () => []);

    points.forEach((p) => {
      const idx = Math.max(0, Math.min(binsCount - 1, Math.floor(((p.y - minY) / spanY) * binsCount)));
      bins[idx].push(p.x);
    });

    const centers = bins
      .filter((b) => b.length >= 2)
      .map((b) => {
        const minX = Math.min(...b);
        const maxX = Math.max(...b);
        return (minX + maxX) / 2;
      });

    if (!centers.length) return null;
    centers.sort((a, b) => a - b);
    return centers[Math.floor(centers.length / 2)];
  }

  localAtY(yPx, xPx = null) {
    const {
      pixelTopY,
      pixelBottomY,
      rTop,
      rBottom,
      realHeight,
      pixelHeight,
      aspectRatioCorrection,
      pixelCenterX,
      perspectiveFactor,
      topRimCurvePx,
      bottomRimCurvePx
    } = this.params;

    const tLinearRaw = (yPx - pixelTopY) / (pixelBottomY - pixelTopY);
    const tLinear = Math.max(0, Math.min(1, tLinearRaw));

    // Elliptic rim correction:
    // In perspective, top/bottom rims are seen as arcs. We use an ellipse-like
    // profile so that points on the same physical rim are mapped to similar t.
    let yCorrected = yPx;
    if (xPx !== null) {
      const radiusAtT = rTop + (rBottom - rTop) * tLinear;
      const mmPerPxY = realHeight / pixelHeight;
      const radiusPxApprox = Math.max(1e-6, (radiusAtT / mmPerPxY) * aspectRatioCorrection);
      const xNorm = Math.max(-1, Math.min(1, (xPx - pixelCenterX) / radiusPxApprox));
      const ellipseProfile = Math.sqrt(Math.max(0, 1 - xNorm * xNorm)); // max at center, 0 at edges
      const topSag = topRimCurvePx * ellipseProfile;
      const bottomSag = bottomRimCurvePx * ellipseProfile;
      const rimSag = topSag * (1 - tLinear) + bottomSag * tLinear;
      yCorrected = yPx - rimSag;
    }

    const tRaw = (yCorrected - pixelTopY) / (pixelBottomY - pixelTopY);
    const t = Math.max(0, Math.min(1, tRaw));

    const radiusMm = rTop + (rBottom - rTop) * t;
    const mmPerPxBase = realHeight / pixelHeight;
    // Perspective depth scaling:
    // mm/px changes along Y (near/far parts of bucket). Positive perspectiveFactor
    // increases mm/px towards the bottom, negative towards the top.
    const depthScale = 1 + perspectiveFactor * (tLinear - 0.5);
    const mmPerPxY = mmPerPxBase * Math.max(0.2, depthScale);

    // Horizontal px/mm can differ from vertical due to perspective/lens/aspect distortion.
    const radiusPx = (radiusMm / mmPerPxY) * aspectRatioCorrection;
    return { t, radiusMm, radiusPx, mmPerPxY };
  }

  toUnrolledPoint(point) {
    const {
      pixelCenterX,
      rTop,
      rBottom,
      realHeight,
      distortionK,
      frameWidthPx,
      frameHeightPx,
      pixelTopY,
      pixelBottomY,
      visibleAngleFactor
    } = this.params;
    const { x, y } = point;
    const local = this.localAtY(y, x);
    const xCentered = x - pixelCenterX;

    // Optional radial distortion compensation.
    // x_corr = x_centered * (1 + k * r^2), where r is normalized radius from image center.
    const centerY = (pixelTopY + pixelBottomY) / 2;
    const halfW = frameWidthPx > 0 ? frameWidthPx / 2 : Math.max(local.radiusPx * 1.5, 1);
    const halfH = frameHeightPx > 0 ? frameHeightPx / 2 : Math.max(Math.abs(pixelBottomY - pixelTopY) / 2, 1);
    const rx = xCentered / Math.max(halfW, 1e-6);
    const ry = (y - centerY) / Math.max(halfH, 1e-6);
    const r2 = rx * rx + ry * ry;
    const xCorrected = xCentered * (1 + distortionK * r2);

    // Clamp denominator to avoid unstable arcsin near degenerate radii.
    const safeRadiusPx = Math.max(local.radiusPx, 4.0);
    const sinArg = Math.max(-1, Math.min(1, xCorrected / safeRadiusPx));
    const alpha = Math.asin(sinArg) * visibleAngleFactor;

    const slantHeight = Math.sqrt((rTop - rBottom) ** 2 + realHeight ** 2);
    const v = local.t * slantHeight;
    const u = local.radiusMm * alpha;

    const edgeRatio = Math.abs(xCorrected) / safeRadiusPx;
    return { u, v, edgeRatio, t: local.t, alpha, xCorrected, radiusPx: local.radiusPx };
  }
}

const shoelaceArea = (pts) => {
  if (!Array.isArray(pts) || pts.length < 3) return 0;
  let sum = 0;
  for (let i = 0; i < pts.length; i += 1) {
    const a = pts[i];
    const b = pts[(i + 1) % pts.length];
    sum += a.u * b.v - b.u * a.v;
  }
  return Math.abs(sum) / 2;
};

export const calculate = (points, params) => {
  if (!Array.isArray(points) || points.length < 3) {
    return { areaMm2: 0, errorPercent: 0, unrolled: [], debugData: { unrolled: [] } };
  }

  const calculator = new BucketCalculator(params);
  const p = calculator.params;
  const unrolled = points.map((p) => calculator.toUnrolledPoint(p));
  const rawAreaMm2 = shoelaceArea(unrolled);

  // Simple rectangle-based area estimate for sanity check / debugging.
  // This estimate ignores cylindrical arc geometry, but should remain in the same order
  // of magnitude. If unrolled area is far above this value, arcsin mapping likely over-stretches edges.
  const xs = points.map((pt) => pt.x);
  const ys = points.map((pt) => pt.y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const widthPx = Math.max(1e-6, maxX - minX);
  const heightPx = Math.max(1e-6, maxY - minY);

  const localMid = calculator.localAtY((minY + maxY) / 2, (minX + maxX) / 2);
  const mmPerPxY = localMid.mmPerPxY;
  const mmPerPxX = mmPerPxY / p.aspectRatioCorrection;
  const simpleRectAreaMm2 = widthPx * mmPerPxX * heightPx * mmPerPxY;

  // Arc-width cap based on visible span and current local radius.
  const centerY = (minY + maxY) / 2;
  const localCenter = calculator.localAtY(centerY, (minX + maxX) / 2);
  const radiusPx = Math.max(4.0, localCenter.radiusPx);
  const halfSpanRatio = Math.max(0, Math.min(1, (widthPx / 2) / radiusPx));
  const alphaSpan = Math.asin(halfSpanRatio) * p.visibleAngleFactor;
  const widthArcMm = 2 * localCenter.radiusMm * alphaSpan;
  const heightMm = heightPx * mmPerPxY;
  const arcRectAreaMm2 = Math.max(1e-6, widthArcMm * heightMm);

  // Keep raw geometric area as primary result.
  // Sanity values are kept in debugData for diagnostics, not as a hard clamp.
  const sanityCapMm2 = Math.min(simpleRectAreaMm2 * 2.25, arcRectAreaMm2 * 2.25);
  const areaMm2 = rawAreaMm2;

  // Simple uncertainty proxy: grows near visible edge where projection distortion is maximal.
  const avgEdgeRatio =
    unrolled.reduce((acc, p) => acc + Math.min(1, Math.max(0, p.edgeRatio)), 0) / unrolled.length;
  const errorPercent = Math.min(35, Math.max(1, Math.round((avgEdgeRatio ** 3) * 35)));

  return {
    areaMm2,
    errorPercent,
    unrolled,
    debugData: {
      unrolled,
      avgEdgeRatio,
      suggestedCenterX: BucketCalculator.suggestCenterX(points),
      rawAreaMm2,
      simpleRectAreaMm2,
      arcRectAreaMm2,
      sanityCapMm2
    }
  };
};
