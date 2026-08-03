package com.example.iml.geometry.analysis;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Опциональная сегментация полосы стыка внутри joint ROI:
 * бинаризация → крупнейший вытянутый blob → две кромки → {@code fitLine} (математическое
 * продление) → параллельность / ширина / taper.
 *
 * <p>На типичном joint ROI (десятки–сотни px) занимает единицы миллисекунд.
 */
public final class LabelSeamBandSegmenter {

    private static final int MIN_BAND_PIXELS = 40;
    private static final int EDGE_SAMPLE_COUNT = 12;
    private static final int MIN_EDGE_POINTS = 6;

    private LabelSeamBandSegmenter() {
    }

    /**
     * @param expectedAxisDeg seam long-axis degrees [0,180), or NaN
     * @return found result, or empty if segmentation failed
     */
    public static LabelSeamAnalyzer.Result analyze(
            Mat bgrRoi,
            Mat roiMask8u,
            double pixelsToMm,
            double minWidthMm,
            double maxWidthMm,
            double expectedAxisDeg
    ) {
        if (bgrRoi == null || bgrRoi.empty() || bgrRoi.cols() < 8 || bgrRoi.rows() < 8) {
            return LabelSeamAnalyzer.Result.empty(0.0);
        }
        double safePixelsToMm = pixelsToMm > 1e-9 ? pixelsToMm : 0.02;
        double axisDeg = Double.isNaN(expectedAxisDeg) ? estimateAxisFromRoiShape(bgrRoi) : expectedAxisDeg;

        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat binary = new Mat();
        Mat inverted = new Mat();
        Mat masked = new Mat();
        Mat morph = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        Mat component = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat bestBand = null;
        int bestArea = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        try {
            if (bgrRoi.channels() >= 3) {
                Imgproc.cvtColor(bgrRoi, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                bgrRoi.copyTo(gray);
            }
            Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);

            for (boolean invert : new boolean[]{false, true}) {
                Imgproc.threshold(blurred, binary, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
                if (invert) {
                    Core.bitwise_not(binary, inverted);
                    inverted.copyTo(binary);
                }
                if (roiMask8u != null && !roiMask8u.empty()
                        && roiMask8u.rows() == binary.rows() && roiMask8u.cols() == binary.cols()) {
                    Core.bitwise_and(binary, roiMask8u, masked);
                } else {
                    binary.copyTo(masked);
                }
                Imgproc.morphologyEx(masked, morph, Imgproc.MORPH_CLOSE, kernel);
                Imgproc.morphologyEx(morph, morph, Imgproc.MORPH_OPEN, kernel);

                int n = Imgproc.connectedComponentsWithStats(morph, labels, stats, centroids, 8, CvType.CV_32S);
                for (int label = 1; label < n; label++) {
                    int area = (int) stats.get(label, Imgproc.CC_STAT_AREA)[0];
                    if (area < MIN_BAND_PIXELS) {
                        continue;
                    }
                    int w = (int) stats.get(label, Imgproc.CC_STAT_WIDTH)[0];
                    int h = (int) stats.get(label, Imgproc.CC_STAT_HEIGHT)[0];
                    if (w < 3 || h < 3) {
                        continue;
                    }
                    double elong = elongationAlongAxis(w, h, axisDeg);
                    double score = area * (1.0 + elong);
                    if (score <= bestScore) {
                        continue;
                    }
                    Core.compare(labels, new Scalar(label), component, Core.CMP_EQ);
                    component.convertTo(component, CvType.CV_8U, 255.0);
                    if (bestBand != null) {
                        bestBand.release();
                    }
                    bestBand = component.clone();
                    bestScore = score;
                    bestArea = area;
                }
            }

            if (bestBand == null) {
                return LabelSeamAnalyzer.Result.empty(0.0);
            }

            EdgePair edges = sampleEdgesAlongAxis(bestBand, axisDeg);
            if (edges == null) {
                return LabelSeamAnalyzer.Result.empty(
                        clamp01(bestArea / (double) (bgrRoi.cols() * bgrRoi.rows()) * 4.0));
            }
            FittedLine lineA = fitEdge(edges.sideA());
            FittedLine lineB = fitEdge(edges.sideB());
            if (lineA == null || lineB == null) {
                return LabelSeamAnalyzer.Result.empty(0.05);
            }

            double parallelismDeg = LabelSeamAnalyzer.smallestAngleDiffDeg(lineA.angleDeg(), lineB.angleDeg());
            WidthStats width = measureWidthAlongAxis(lineA, lineB, edges.axisSamples());
            double widthMm = width.meanPx() * safePixelsToMm;
            double widthStartMm = width.startPx() * safePixelsToMm;
            double widthEndMm = width.endPx() * safePixelsToMm;
            double taperMm = Math.abs(widthStartMm - widthEndMm);

            double visibility = clamp01(bestArea / (double) (bgrRoi.cols() * bgrRoi.rows()) * 6.0);
            visibility = Math.max(visibility, clamp01(edges.sideA().size() / 20.0));

            double defectMm;
            if (widthMm >= minWidthMm && widthMm <= maxWidthMm) {
                defectMm = 0.0;
            } else if (widthMm < minWidthMm) {
                defectMm = minWidthMm - widthMm;
            } else {
                defectMm = widthMm - maxWidthMm;
            }
            return new LabelSeamAnalyzer.Result(
                    true,
                    parallelismDeg,
                    widthMm,
                    widthStartMm,
                    widthEndMm,
                    taperMm,
                    visibility,
                    defectMm
            );
        } finally {
            if (bestBand != null) {
                bestBand.release();
            }
            release(gray, blurred, binary, inverted, masked, morph, labels, stats, centroids, component, kernel);
        }
    }

    /**
     * Walk along seam axis; for each sample find min/max extent perpendicular → two edge polylines.
     */
    private static EdgePair sampleEdgesAlongAxis(Mat band, double axisDeg) {
        double rad = Math.toRadians(axisDeg);
        double ux = Math.cos(rad);
        double uy = Math.sin(rad);
        double px = -uy;
        double py = ux;

        int cols = band.cols();
        int rows = band.rows();
        double cx = (cols - 1) * 0.5;
        double cy = (rows - 1) * 0.5;
        double halfSpan = Math.hypot(cols, rows) * 0.55;

        List<Point> sideA = new ArrayList<>();
        List<Point> sideB = new ArrayList<>();
        List<Point> axisSamples = new ArrayList<>();

        int steps = Math.max(EDGE_SAMPLE_COUNT, (int) (halfSpan / 4.0));
        for (int i = 0; i < steps; i++) {
            double t = -halfSpan + (2.0 * halfSpan) * (i + 0.5) / steps;
            double ox = cx + ux * t;
            double oy = cy + uy * t;
            Double sMin = null;
            Double sMax = null;
            int perpSteps = Math.max(16, (int) (Math.min(cols, rows) * 0.9));
            double perpHalf = Math.min(cols, rows) * 0.55;
            for (int j = 0; j < perpSteps; j++) {
                double s = -perpHalf + (2.0 * perpHalf) * j / (perpSteps - 1.0);
                int x = (int) Math.round(ox + px * s);
                int y = (int) Math.round(oy + py * s);
                if (x < 0 || y < 0 || x >= cols || y >= rows) {
                    continue;
                }
                double[] v = band.get(y, x);
                if (v != null && v[0] > 0) {
                    if (sMin == null || s < sMin) {
                        sMin = s;
                    }
                    if (sMax == null || s > sMax) {
                        sMax = s;
                    }
                }
            }
            if (sMin == null || sMax == null || Math.abs(sMax - sMin) < 1.5) {
                continue;
            }
            sideA.add(new Point(ox + px * sMin, oy + py * sMin));
            sideB.add(new Point(ox + px * sMax, oy + py * sMax));
            axisSamples.add(new Point(ox, oy));
        }
        if (sideA.size() < MIN_EDGE_POINTS || sideB.size() < MIN_EDGE_POINTS) {
            return null;
        }
        return new EdgePair(sideA, sideB, axisSamples);
    }

    private static FittedLine fitEdge(List<Point> points) {
        if (points.size() < MIN_EDGE_POINTS) {
            return null;
        }
        Mat pts = new Mat(points.size(), 1, CvType.CV_32FC2);
        Mat line = new Mat();
        try {
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                pts.put(i, 0, (float) p.x, (float) p.y);
            }
            Imgproc.fitLine(pts, line, Imgproc.DIST_L2, 0, 0.01, 0.01);
            double vx;
            double vy;
            double x0;
            double y0;
            if (line.rows() >= 4) {
                vx = line.get(0, 0)[0];
                vy = line.get(1, 0)[0];
                x0 = line.get(2, 0)[0];
                y0 = line.get(3, 0)[0];
            } else {
                double[] v = new double[4];
                line.get(0, 0, v);
                vx = v[0];
                vy = v[1];
                x0 = v[2];
                y0 = v[3];
            }
            double norm = Math.hypot(vx, vy);
            if (norm < 1e-9) {
                return null;
            }
            return new FittedLine(vx / norm, vy / norm, x0, y0);
        } finally {
            pts.release();
            line.release();
        }
    }

    private static WidthStats measureWidthAlongAxis(FittedLine a, FittedLine b, List<Point> axisSamples) {
        List<Double> widths = new ArrayList<>(axisSamples.size());
        for (Point p : axisSamples) {
            double ax = a.x0();
            double ay = a.y0();
            double t = (p.x - ax) * a.vx() + (p.y - ay) * a.vy();
            double qx = ax + a.vx() * t;
            double qy = ay + a.vy() * t;
            widths.add(pointToLineDistance(qx, qy, b));
        }
        return new WidthStats(widths);
    }

    private static double pointToLineDistance(double px, double py, FittedLine line) {
        return Math.abs((px - line.x0()) * (-line.vy()) + (py - line.y0()) * line.vx());
    }

    private static double elongationAlongAxis(int bboxW, int bboxH, double axisDeg) {
        double rad = Math.toRadians(axisDeg);
        double along = Math.abs(bboxW * Math.cos(rad)) + Math.abs(bboxH * Math.sin(rad));
        double across = Math.abs(bboxW * Math.sin(rad)) + Math.abs(bboxH * Math.cos(rad));
        if (across < 1e-6) {
            return 1.0;
        }
        return Math.min(8.0, along / across);
    }

    private static double estimateAxisFromRoiShape(Mat roi) {
        return roi.cols() >= roi.rows() ? 0.0 : 90.0;
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

    private record EdgePair(List<Point> sideA, List<Point> sideB, List<Point> axisSamples) {
    }

    private record FittedLine(double vx, double vy, double x0, double y0) {
        double angleDeg() {
            return LabelSeamAnalyzer.normalizeAngleDeg(Math.toDegrees(Math.atan2(vy, vx)));
        }
    }

    private record WidthStats(List<Double> samplesPx) {
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

        double startPx() {
            return samplesPx.isEmpty() ? 0.0 : samplesPx.get(0);
        }

        double endPx() {
            return samplesPx.isEmpty() ? 0.0 : samplesPx.get(samplesPx.size() - 1);
        }
    }
}
