package com.example.iml.geometry.analysis;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Контроль шва этикетки внутри joint ROI: две кромки → параллельность, ширина, видимость.
 *
 * <p>Пары линий скорятся с учётом ожидаемой ширины шва, чтобы Canny double-edge
 * (микрозазор ~2–5 px) не выигрывал у реального стыка.
 */
public final class LabelSeamAnalyzer {

    private static final double CANNY_LOW = 50.0;
    private static final double CANNY_HIGH = 150.0;
    private static final double MAX_PAIR_ANGLE_DEG = 25.0;
    private static final int MAX_CANDIDATE_LINES = 24;
    /** Absolute floor so near-coincident edges are never treated as a seam. */
    private static final double ABS_MIN_PAIR_DIST_PX = 2.0;

    private LabelSeamAnalyzer() {
    }

    public record Result(
            boolean found,
            double parallelismDeg,
            double widthMm,
            double visibility,
            double defectMm
    ) {
        public static Result empty(double visibility) {
            return new Result(false, 180.0, 0.0, clamp01(visibility), 9999.0);
        }
    }

    public static Result analyze(Mat bgrRoi, double pixelsToMm, double minWidthMm, double maxWidthMm) {
        if (bgrRoi == null || bgrRoi.empty() || bgrRoi.cols() < 8 || bgrRoi.rows() < 8) {
            return Result.empty(0.0);
        }
        double safePixelsToMm = pixelsToMm > 1e-9 ? pixelsToMm : 0.02;
        double minWidthPx = Math.max(0.0, minWidthMm) / safePixelsToMm;
        double maxWidthPx = Math.max(minWidthPx, Math.max(0.0, maxWidthMm) / safePixelsToMm);
        // Reject Canny double-edges: require at least half of configured min seam width.
        double minPairDistPx = Math.max(ABS_MIN_PAIR_DIST_PX, 0.5 * minWidthPx);
        double maxPairDistPx = maxWidthPx > 0.0 ? maxWidthPx * 1.25 : Double.POSITIVE_INFINITY;
        double midBandPx = (minWidthPx + maxWidthPx) * 0.5;

        Mat gray = new Mat();
        Mat edges = new Mat();
        Mat closed = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat linesMat = new Mat();
        try {
            if (bgrRoi.channels() >= 3) {
                Imgproc.cvtColor(bgrRoi, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                bgrRoi.copyTo(gray);
            }
            Imgproc.Canny(gray, edges, CANNY_LOW, CANNY_HIGH);
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);

            double edgeDensity = Core.countNonZero(closed) / (double) (closed.cols() * closed.rows());
            double diagonal = Math.hypot(closed.cols(), closed.rows());
            double minLineLength = Math.max(12.0, Math.min(closed.cols(), closed.rows()) * 0.35);
            Imgproc.HoughLinesP(
                    closed,
                    linesMat,
                    1.0,
                    Math.PI / 180.0,
                    28,
                    minLineLength,
                    Math.max(4.0, minLineLength * 0.15)
            );

            List<SeamLine> lines = toLines(linesMat);
            lines.sort(Comparator.comparingDouble(SeamLine::length).reversed());
            if (lines.size() > MAX_CANDIDATE_LINES) {
                lines = new ArrayList<>(lines.subList(0, MAX_CANDIDATE_LINES));
            }

            SeamPair best = findBestPair(lines, minPairDistPx, maxPairDistPx, midBandPx);
            double fallbackVisibility = clamp01(edgeDensity * 4.0);
            if (best == null) {
                return Result.empty(fallbackVisibility);
            }

            double widthPx = best.distancePx();
            double widthMm = widthPx * safePixelsToMm;
            double visibility = clamp01((best.a().length() + best.b().length()) / (2.0 * diagonal));
            visibility = Math.max(visibility, clamp01(edgeDensity * 3.0));

            // Micro-width measurement is invalid (still a double-edge leak) — inconclusive, not a defect.
            double measurementFloorMm = Math.max(safePixelsToMm * ABS_MIN_PAIR_DIST_PX, minWidthMm * 0.5);
            if (widthMm < measurementFloorMm) {
                return Result.empty(visibility);
            }

            double defectMm;
            if (widthMm >= minWidthMm && widthMm <= maxWidthMm) {
                defectMm = 0.0;
            } else {
                defectMm = widthMm < minWidthMm ? (minWidthMm - widthMm) : (widthMm - maxWidthMm);
            }
            return new Result(true, best.angleDiffDeg(), widthMm, visibility, defectMm);
        } finally {
            release(gray, edges, closed, kernel, linesMat);
        }
    }

    private static SeamPair findBestPair(
            List<SeamLine> lines,
            double minPairDistPx,
            double maxPairDistPx,
            double midBandPx
    ) {
        SeamPair best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
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
                // Prefer long edges near the expected seam-width band; penalize outliers.
                double widthPenalty = Math.abs(distancePx - midBandPx);
                double score = a.length() + b.length() - widthPenalty;
                if (score > bestScore) {
                    bestScore = score;
                    best = new SeamPair(a, b, angleDiff, distancePx);
                }
            }
        }
        return best;
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
        double dx = b.x2() - b.x1();
        double dy = b.y2() - b.y1();
        double norm = Math.hypot(dx, dy);
        if (norm < 1e-6) {
            return 0.0;
        }
        // ax + by + c = 0 for line b
        double aa = dy / norm;
        double bb = -dx / norm;
        double cc = -(aa * b.x1() + bb * b.y1());
        double midX = (a.x1() + a.x2()) * 0.5;
        double midY = (a.y1() + a.y2()) * 0.5;
        return Math.abs(aa * midX + bb * midY + cc);
    }

    static double smallestAngleDiffDeg(double a, double b) {
        double diff = Math.abs(a - b) % 180.0;
        if (diff > 90.0) {
            diff = 180.0 - diff;
        }
        return diff;
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
            double angle = Math.toDegrees(Math.atan2(dy, dx));
            if (angle < 0.0) {
                angle += 180.0;
            }
            if (angle >= 180.0) {
                angle -= 180.0;
            }
            return new SeamLine(x1, y1, x2, y2, angle, length);
        }
    }

    private record SeamPair(SeamLine a, SeamLine b, double angleDiffDeg, double distancePx) {
    }
}
