package com.example.iml.geometry.analysis;

import com.example.iml.geometry.dto.NormPoint;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Контроль шва этикетки внутри joint ROI: сегментация полосы зазора → ширина в нескольких
 * сечениях вдоль оси, параллельность кромок, видимость.
 *
 * <p>Ось шва берётся из ориентированного ROI-полигона (длинные стороны) — работает и для
 * горизонтального, и для наклонного стыка. Пары линий фильтруются по этой оси, чтобы
 * не брать перпендикулярный шум. Taper меряется вдоль оси шва (начало/конец), а не по Y кадра.
 */
public final class LabelSeamAnalyzer {

    private static final double CANNY_LOW = 40.0;
    private static final double CANNY_HIGH = 120.0;
    /** Max angle between the two seam edges (parallelism). */
    private static final double MAX_PAIR_ANGLE_DEG = 25.0;
    /** Max deviation of a candidate edge from the expected seam axis (ROI long side). */
    private static final double MAX_AXIS_ALIGN_DEG = 35.0;
    private static final int MAX_CANDIDATE_LINES = 24;
    /** Absolute floor so near-coincident edges are never treated as a seam. */
    private static final double ABS_MIN_PAIR_DIST_PX = 2.0;
    private static final int WIDTH_SAMPLE_COUNT = 5;

    private LabelSeamAnalyzer() {
    }

    public record Result(
            boolean found,
            double parallelismDeg,
            double widthMm,
            double widthTopMm,
            double widthBottomMm,
            double taperMm,
            double visibility,
            double defectMm
    ) {
        public static Result empty(double visibility) {
            return new Result(false, 180.0, 0.0, 0.0, 0.0, 0.0, clamp01(visibility), 9999.0);
        }
    }

    public static Result analyze(Mat bgrRoi, double pixelsToMm, double minWidthMm, double maxWidthMm) {
        return analyze(bgrRoi, null, pixelsToMm, minWidthMm, maxWidthMm, Double.NaN, false);
    }

    public static Result analyze(
            Mat bgrRoi,
            Mat roiMask8u,
            double pixelsToMm,
            double minWidthMm,
            double maxWidthMm
    ) {
        return analyze(bgrRoi, roiMask8u, pixelsToMm, minWidthMm, maxWidthMm, Double.NaN, false);
    }

    public static Result analyze(
            Mat bgrRoi,
            Mat roiMask8u,
            double pixelsToMm,
            double minWidthMm,
            double maxWidthMm,
            double expectedAxisDeg
    ) {
        return analyze(bgrRoi, roiMask8u, pixelsToMm, minWidthMm, maxWidthMm, expectedAxisDeg, false);
    }

    /**
     * @param roiMask8u optional mask in ROI coordinates (nonzero = keep); may be null
     * @param expectedAxisDeg seam-edge angle in degrees [0, 180), or NaN if unknown
     * @param seamSegmentationEnabled if true, try band segmentation + fitLine edges first
     */
    public static Result analyze(
            Mat bgrRoi,
            Mat roiMask8u,
            double pixelsToMm,
            double minWidthMm,
            double maxWidthMm,
            double expectedAxisDeg,
            boolean seamSegmentationEnabled
    ) {
        if (seamSegmentationEnabled) {
            Result segmented = LabelSeamBandSegmenter.analyze(
                    bgrRoi, roiMask8u, pixelsToMm, minWidthMm, maxWidthMm, expectedAxisDeg);
            if (segmented.found()) {
                return segmented;
            }
            // Fall back to Hough pair if segmentation did not lock onto a band.
        }
        return analyzeHough(bgrRoi, roiMask8u, pixelsToMm, minWidthMm, maxWidthMm, expectedAxisDeg);
    }

    private static Result analyzeHough(
            Mat bgrRoi,
            Mat roiMask8u,
            double pixelsToMm,
            double minWidthMm,
            double maxWidthMm,
            double expectedAxisDeg
    ) {
        if (bgrRoi == null || bgrRoi.empty() || bgrRoi.cols() < 8 || bgrRoi.rows() < 8) {
            return Result.empty(0.0);
        }
        double safePixelsToMm = pixelsToMm > 1e-9 ? pixelsToMm : 0.02;
        double minWidthPx = Math.max(0.0, minWidthMm) / safePixelsToMm;
        double maxWidthPx = Math.max(minWidthPx, Math.max(0.0, maxWidthMm) / safePixelsToMm);
        // Absolute floor + small fraction of min — still allow measuring "too narrow" seams.
        double minPairDistPx = Math.max(ABS_MIN_PAIR_DIST_PX, 0.15 * minWidthPx);
        double maxPairDistPx = maxWidthPx > 0.0 ? maxWidthPx * 1.25 : Double.POSITIVE_INFINITY;
        double midBandPx = (minWidthPx + maxWidthPx) * 0.5;
        boolean hasAxis = !Double.isNaN(expectedAxisDeg);

        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat closed = new Mat();
        Mat maskedEdges = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat linesMat = new Mat();
        Mat bandMask = new Mat();
        try {
            if (bgrRoi.channels() >= 3) {
                Imgproc.cvtColor(bgrRoi, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                bgrRoi.copyTo(gray);
            }
            // Soft blur helps slightly defocused rim seams keep continuous edges.
            Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);
            Imgproc.Canny(blurred, edges, CANNY_LOW, CANNY_HIGH);
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
            if (roiMask8u != null && !roiMask8u.empty()
                    && roiMask8u.rows() == closed.rows() && roiMask8u.cols() == closed.cols()) {
                Core.bitwise_and(closed, roiMask8u, maskedEdges);
            } else {
                closed.copyTo(maskedEdges);
            }

            double edgeDensity = Core.countNonZero(maskedEdges)
                    / (double) (maskedEdges.cols() * maskedEdges.rows());
            // Prefer length along the longer ROI side so horizontal strips still get long Hough lines.
            double span = Math.max(maskedEdges.cols(), maskedEdges.rows());
            double minLineLength = Math.max(12.0, span * 0.28);
            Imgproc.HoughLinesP(
                    maskedEdges,
                    linesMat,
                    1.0,
                    Math.PI / 180.0,
                    20,
                    minLineLength,
                    Math.max(4.0, minLineLength * 0.12)
            );

            List<SeamLine> lines = toLines(linesMat);
            if (hasAxis) {
                lines = filterByAxis(lines, expectedAxisDeg, MAX_AXIS_ALIGN_DEG);
            }
            lines.sort(Comparator.comparingDouble(SeamLine::length).reversed());
            if (lines.size() > MAX_CANDIDATE_LINES) {
                lines = new ArrayList<>(lines.subList(0, MAX_CANDIDATE_LINES));
            }

            SeamPair best = findBestPair(lines, minPairDistPx, maxPairDistPx, midBandPx, expectedAxisDeg);
            double fallbackVisibility = clamp01(edgeDensity * 4.0);
            if (best == null) {
                return Result.empty(fallbackVisibility);
            }

            WidthProfile profile = sampleWidthProfile(best.a(), best.b(), WIDTH_SAMPLE_COUNT);
            if (profile.samplesPx.isEmpty()) {
                return Result.empty(fallbackVisibility);
            }

            fillSeamBandMask(bandMask, maskedEdges.rows(), maskedEdges.cols(), best.a(), best.b());
            double bandCoverage = 0.0;
            if (!bandMask.empty()) {
                int bandPixels = Core.countNonZero(bandMask);
                if (bandPixels > 0) {
                    Mat edgeInBand = new Mat();
                    try {
                        Core.bitwise_and(maskedEdges, bandMask, edgeInBand);
                        bandCoverage = Core.countNonZero(edgeInBand) / (double) bandPixels;
                    } finally {
                        edgeInBand.release();
                    }
                }
            }

            double meanWidthPx = profile.meanPx();
            double widthMm = meanWidthPx * safePixelsToMm;
            double widthTopMm = profile.startPx() * safePixelsToMm;
            double widthBottomMm = profile.endPx() * safePixelsToMm;
            double taperMm = Math.abs(widthTopMm - widthBottomMm);

            double diagonal = Math.hypot(maskedEdges.cols(), maskedEdges.rows());
            double visibility = clamp01((best.a().length() + best.b().length()) / (2.0 * diagonal));
            visibility = Math.max(visibility, clamp01(edgeDensity * 3.0));
            visibility = Math.max(visibility, clamp01(bandCoverage * 2.5));

            // Always report measured width (including below min) so jointMinWidthMm can FAIL.
            double defectMm;
            if (widthMm >= minWidthMm && widthMm <= maxWidthMm) {
                defectMm = 0.0;
            } else if (widthMm < minWidthMm) {
                defectMm = minWidthMm - widthMm;
            } else {
                defectMm = widthMm - maxWidthMm;
            }
            return new Result(
                    true,
                    best.angleDiffDeg(),
                    widthMm,
                    widthTopMm,
                    widthBottomMm,
                    taperMm,
                    visibility,
                    defectMm
            );
        } finally {
            release(gray, blurred, edges, closed, maskedEdges, kernel, linesMat, bandMask);
        }
    }

    /**
     * Long-axis angle of an oriented joint ROI polygon, in ROI-local degrees [0, 180).
     * Uses the two longest edges (seam direction). Returns NaN if polygon is unusable.
     */
    public static double estimateAxisDegFromPolygon(
            List<NormPoint> polygonNorm,
            Rect roiRect,
            int frameWidth,
            int frameHeight
    ) {
        if (polygonNorm == null || polygonNorm.size() < 3 || roiRect == null) {
            return Double.NaN;
        }
        List<Point> local = new ArrayList<>(polygonNorm.size());
        for (NormPoint p : polygonNorm) {
            if (p == null) {
                return Double.NaN;
            }
            double dw = Math.max(1, frameWidth - 1);
            double dh = Math.max(1, frameHeight - 1);
            double px = p.x() * dw - roiRect.x;
            double py = p.y() * dh - roiRect.y;
            local.add(new Point(px, py));
        }
        int n = local.size();
        record Edge(double length, double angleDeg) {
        }
        List<Edge> edges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Point a = local.get(i);
            Point b = local.get((i + 1) % n);
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) {
                continue;
            }
            edges.add(new Edge(len, normalizeAngleDeg(Math.toDegrees(Math.atan2(dy, dx)))));
        }
        if (edges.size() < 2) {
            return Double.NaN;
        }
        edges.sort(Comparator.comparingDouble(Edge::length).reversed());
        // Average the two longest edges (oriented rect: those are the seam-parallel sides).
        double a0 = edges.get(0).angleDeg();
        double a1 = edges.get(1).angleDeg();
        // Bring a1 into the same half-circle neighbourhood as a0 before averaging.
        if (smallestAngleDiffDeg(a0, a1) > 90.0) {
            a1 = normalizeAngleDeg(a1 + 180.0);
        }
        if (Math.abs(a1 - a0) > 90.0) {
            if (a1 > a0) {
                a1 -= 180.0;
            } else {
                a1 += 180.0;
            }
        }
        return normalizeAngleDeg(0.5 * (a0 + a1));
    }

    private static List<SeamLine> filterByAxis(List<SeamLine> lines, double axisDeg, double tolDeg) {
        List<SeamLine> out = new ArrayList<>();
        for (SeamLine line : lines) {
            if (smallestAngleDiffDeg(line.angleDeg(), axisDeg) <= tolDeg) {
                out.add(line);
            }
        }
        return out;
    }

    private static SeamPair findBestPair(
            List<SeamLine> lines,
            double minPairDistPx,
            double maxPairDistPx,
            double midBandPx,
            double expectedAxisDeg
    ) {
        SeamPair best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean hasAxis = !Double.isNaN(expectedAxisDeg);
        for (int i = 0; i < lines.size(); i++) {
            SeamLine a = lines.get(i);
            for (int j = i + 1; j < lines.size(); j++) {
                SeamLine b = lines.get(j);
                double angleDiff = smallestAngleDiffDeg(a.angleDeg(), b.angleDeg());
                if (angleDiff > MAX_PAIR_ANGLE_DEG) {
                    continue;
                }
                double distancePx = distanceBetweenLines(a, b);
                if (distancePx < minPairDistPx || distancePx > maxPairDistPx) {
                    continue;
                }
                double widthPenalty = Math.abs(distancePx - midBandPx);
                double score = a.length() + b.length() - widthPenalty - angleDiff * 2.0;
                if (hasAxis) {
                    double meanAngle = meanAngleDeg(a.angleDeg(), b.angleDeg());
                    double axisSkew = smallestAngleDiffDeg(meanAngle, expectedAxisDeg);
                    score -= axisSkew * 3.0;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new SeamPair(a, b, angleDiff, distancePx);
                }
            }
        }
        return best;
    }

    /**
     * Sample perpendicular gap width along the longer edge; order by position along the seam
     * axis (not image Y), so taper works for horizontal and tilted joints.
     */
    private static WidthProfile sampleWidthProfile(SeamLine a, SeamLine b, int sampleCount) {
        SeamLine guide = a.length() >= b.length() ? a : b;
        SeamLine other = b.equals(guide) ? a : b;
        int n = Math.max(3, sampleCount);
        List<Double> widths = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = (i + 1.0) / (n + 1.0);
            double x = guide.x1() + (guide.x2() - guide.x1()) * t;
            double y = guide.y1() + (guide.y2() - guide.y1()) * t;
            widths.add(pointToLineDistance(x, y, other));
        }
        return new WidthProfile(widths);
    }

    /** Soft segmentation of the strip between the two seam edges (for visibility / debug). */
    private static void fillSeamBandMask(Mat out, int rows, int cols, SeamLine a, SeamLine b) {
        out.create(rows, cols, CvType.CV_8UC1);
        out.setTo(new Scalar(0));
        // Order endpoints so the quad does not self-intersect: walk A then reverse B.
        Point[] poly = new Point[]{
                new Point(a.x1(), a.y1()),
                new Point(a.x2(), a.y2()),
                new Point(b.x2(), b.y2()),
                new Point(b.x1(), b.y1())
        };
        // If A and B run opposite directions, reverse B to keep a coherent strip.
        double dot = (a.x2() - a.x1()) * (b.x2() - b.x1()) + (a.y2() - a.y1()) * (b.y2() - b.y1());
        if (dot < 0) {
            poly[2] = new Point(b.x1(), b.y1());
            poly[3] = new Point(b.x2(), b.y2());
        }
        MatOfPointCompat.fillConvex(out, poly);
    }

    private static List<SeamLine> toLines(Mat linesMat) {
        List<SeamLine> out = new ArrayList<>();
        if (linesMat.empty()) {
            return out;
        }
        for (int i = 0; i < linesMat.rows(); i++) {
            double[] v = linesMat.get(i, 0);
            if (v == null || v.length < 4) {
                continue;
            }
            out.add(SeamLine.fromEndpoints(v[0], v[1], v[2], v[3]));
        }
        return out;
    }

    private static double distanceBetweenLines(SeamLine a, SeamLine b) {
        double midX = (a.x1() + a.x2()) * 0.5;
        double midY = (a.y1() + a.y2()) * 0.5;
        return pointToLineDistance(midX, midY, b);
    }

    private static double pointToLineDistance(double px, double py, SeamLine line) {
        double dx = line.x2() - line.x1();
        double dy = line.y2() - line.y1();
        double norm = Math.hypot(dx, dy);
        if (norm < 1e-6) {
            return 0.0;
        }
        double aa = dy / norm;
        double bb = -dx / norm;
        double cc = -(aa * line.x1() + bb * line.y1());
        return Math.abs(aa * px + bb * py + cc);
    }

    static double smallestAngleDiffDeg(double a, double b) {
        double diff = Math.abs(normalizeAngleDeg(a) - normalizeAngleDeg(b)) % 180.0;
        if (diff > 90.0) {
            diff = 180.0 - diff;
        }
        return diff;
    }

    static double normalizeAngleDeg(double angleDeg) {
        double a = angleDeg % 180.0;
        if (a < 0.0) {
            a += 180.0;
        }
        if (a >= 180.0) {
            a -= 180.0;
        }
        return a;
    }

    private static double meanAngleDeg(double a, double b) {
        double a0 = normalizeAngleDeg(a);
        double a1 = normalizeAngleDeg(b);
        if (Math.abs(a1 - a0) > 90.0) {
            if (a1 > a0) {
                a1 -= 180.0;
            } else {
                a1 += 180.0;
            }
        }
        return normalizeAngleDeg(0.5 * (a0 + a1));
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    private static void release(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) {
                mat.release();
            }
        }
    }

    private record SeamLine(double x1, double y1, double x2, double y2, double angleDeg, double length) {
        static SeamLine fromEndpoints(double x1, double y1, double x2, double y2) {
            double dx = x2 - x1;
            double dy = y2 - y1;
            double length = Math.hypot(dx, dy);
            return new SeamLine(x1, y1, x2, y2, normalizeAngleDeg(Math.toDegrees(Math.atan2(dy, dx))), length);
        }
    }

    private record SeamPair(SeamLine a, SeamLine b, double angleDiffDeg, double distancePx) {
    }

    private record WidthProfile(List<Double> samplesPx) {
        double meanPx() {
            if (samplesPx.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            for (double w : samplesPx) {
                sum += w;
            }
            return sum / samplesPx.size();
        }

        /** Width near the start of the seam axis (first sample along guide). */
        double startPx() {
            return samplesPx.isEmpty() ? 0.0 : samplesPx.get(0);
        }

        /** Width near the end of the seam axis (last sample along guide). */
        double endPx() {
            return samplesPx.isEmpty() ? 0.0 : samplesPx.get(samplesPx.size() - 1);
        }
    }

    /** Tiny helper so we do not leak MatOfPoint across the analyzer API. */
    private static final class MatOfPointCompat {
        private MatOfPointCompat() {
        }

        static void fillConvex(Mat mask, Point[] poly) {
            org.opencv.core.MatOfPoint contour = new org.opencv.core.MatOfPoint(poly);
            try {
                Imgproc.fillConvexPoly(mask, contour, new Scalar(255));
            } finally {
                contour.release();
            }
        }
    }
}
