package com.example.iml.geometry.analysis;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Перекос этикетки относительно жёлтого обода.
 * Метрики: угол клина зазора «низ этикетки» / «верх обода» и |gapLeft − gapRight|.
 * Если жёлтый обод не найден — {@code active=false} (гейт не валит кадр).
 * <p>
 * Вердикт применяется только на joint-камере (см. {@code evaluateRimSkewPass}):
 * соседние вёдра под углом дают оптический клин — это не брак этикетки.
 */
public final class LabelRimSkewAnalyzer {

    private static final int MIN_SAMPLES = 12;
    private static final double MIN_YELLOW_AREA_FRAC = 0.015;
    private static final int SAMPLE_COUNT = 48;

    private LabelRimSkewAnalyzer() {
    }

    public record Result(
            boolean active,
            double skewDeg,
            double gapLeftMm,
            double gapRightMm,
            double gapAsymmetryMm
    ) {
        static Result inactive() {
            return new Result(false, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public static Result analyze(Mat bgrRoi, Mat roiMask8u, double pixelsToMm) {
        if (bgrRoi == null || bgrRoi.empty() || bgrRoi.channels() < 3) {
            return Result.inactive();
        }
        double safePxToMm = pixelsToMm > 1e-9 ? pixelsToMm : 0.02;

        Mat hsv = new Mat();
        Mat yellow = new Mat();
        Mat morph = new Mat();
        Mat kernel = null;
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        try {
            Imgproc.cvtColor(bgrRoi, hsv, Imgproc.COLOR_BGR2HSV);
            // Яркий насыщенный жёлтый пластик обода (OpenCV H: 0..180).
            Core.inRange(hsv, new Scalar(15, 70, 90), new Scalar(45, 255, 255), yellow);
            if (roiMask8u != null && !roiMask8u.empty() && roiMask8u.size().equals(yellow.size())) {
                Core.bitwise_and(yellow, roiMask8u, yellow);
            }
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.morphologyEx(yellow, morph, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(morph, morph, Imgproc.MORPH_OPEN, kernel);

            double yellowFrac = Core.countNonZero(morph) / (double) (morph.cols() * morph.rows());
            if (yellowFrac < MIN_YELLOW_AREA_FRAC) {
                return Result.inactive();
            }

            Imgproc.findContours(morph, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            Rect yellowBox = largestContourRect(contours, morph.cols(), morph.rows());
            if (yellowBox == null || yellowBox.width < 20 || yellowBox.height < 8) {
                return Result.inactive();
            }

            // Обод обычно в нижней половине ROI; отсекаем блики сверху.
            int minY = (int) Math.round(morph.rows() * 0.25);
            if (yellowBox.y + yellowBox.height < minY) {
                return Result.inactive();
            }

            List<GapSample> samples = sampleGaps(bgrRoi, morph, yellowBox);
            if (samples.size() < MIN_SAMPLES) {
                return Result.inactive();
            }

            double leftGapPx = meanGap(samples, 0.0, 0.35);
            double rightGapPx = meanGap(samples, 0.65, 1.0);
            if (Double.isNaN(leftGapPx) || Double.isNaN(rightGapPx)) {
                return Result.inactive();
            }

            double asymmetryMm = Math.abs(leftGapPx - rightGapPx) * safePxToMm;
            double skewDeg = estimateSkewDeg(leftGapPx, rightGapPx, yellowBox.width);
            return new Result(
                    true,
                    skewDeg,
                    leftGapPx * safePxToMm,
                    rightGapPx * safePxToMm,
                    asymmetryMm
            );
        } finally {
            release(hsv, yellow, morph, kernel, hierarchy);
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    private static Rect largestContourRect(List<MatOfPoint> contours, int cols, int rows) {
        double bestArea = 0.0;
        Rect best = null;
        for (MatOfPoint c : contours) {
            double area = Imgproc.contourArea(c);
            if (area < bestArea) {
                continue;
            }
            Rect r = Imgproc.boundingRect(c);
            // Игнор мелких бликов и слишком высоких «столбов» (не обод).
            if (r.width < cols * 0.15 || r.height > rows * 0.55) {
                continue;
            }
            bestArea = area;
            best = r;
        }
        return best;
    }

    private record GapSample(double xNorm, double gapPx, double rimY, double labelY) {
    }

    private static List<GapSample> sampleGaps(Mat bgr, Mat yellowMask, Rect yellowBox) {
        List<GapSample> out = new ArrayList<>(SAMPLE_COUNT);
        int x0 = yellowBox.x + Math.max(2, yellowBox.width / 20);
        int x1 = yellowBox.x + yellowBox.width - Math.max(2, yellowBox.width / 20);
        if (x1 <= x0) {
            return out;
        }

        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                double t = (i + 0.5) / SAMPLE_COUNT;
                int x = (int) Math.round(x0 + t * (x1 - x0));
                Integer rimY = findRimTopY(yellowMask, x, yellowBox);
                if (rimY == null) {
                    continue;
                }
                Integer labelY = findLabelBottomY(gray, yellowMask, x, rimY);
                if (labelY == null || labelY >= rimY) {
                    continue;
                }
                double gap = rimY - labelY;
                if (gap < 1.0 || gap > yellowMask.rows() * 0.45) {
                    continue;
                }
                out.add(new GapSample(t, gap, rimY, labelY));
            }
        } finally {
            gray.release();
        }
        return out;
    }

    /** Верхняя кромка жёлтого в колонке (минимальный y с yellow). */
    private static Integer findRimTopY(Mat yellowMask, int x, Rect yellowBox) {
        int yStart = Math.max(0, yellowBox.y - 4);
        int yEnd = Math.min(yellowMask.rows() - 1, yellowBox.y + yellowBox.height);
        Integer top = null;
        for (int y = yStart; y <= yEnd; y++) {
            double[] v = yellowMask.get(y, x);
            if (v != null && v[0] > 0) {
                top = y;
                break;
            }
        }
        return top;
    }

    /**
     * Низ этикетки: над ободом ищем переход из тёмного зазора в «тело» (ярче / не жёлтое).
     */
    private static Integer findLabelBottomY(Mat gray, Mat yellowMask, int x, int rimY) {
        int yMin = Math.max(0, rimY - (int) Math.round(gray.rows() * 0.4));
        // Сначала подняться через тёмный зазор (если есть).
        int y = rimY - 1;
        int darkRun = 0;
        while (y >= yMin) {
            double[] yel = yellowMask.get(y, x);
            if (yel != null && yel[0] > 0) {
                y--;
                continue;
            }
            double[] g = gray.get(y, x);
            double gv = g == null || g.length == 0 ? 255.0 : g[0];
            if (gv < 70.0) {
                darkRun++;
                y--;
                continue;
            }
            break;
        }
        // Дальше — первый заметно более яркий пиксель = низ этикетки/корпуса.
        while (y >= yMin) {
            double[] yel = yellowMask.get(y, x);
            if (yel != null && yel[0] > 0) {
                y--;
                continue;
            }
            double[] g = gray.get(y, x);
            double gv = g == null || g.length == 0 ? 0.0 : g[0];
            if (gv >= 85.0 || darkRun == 0 && gv >= 60.0) {
                return y;
            }
            y--;
        }
        return null;
    }

    private static double meanGap(List<GapSample> samples, double t0, double t1) {
        double sum = 0.0;
        int n = 0;
        for (GapSample s : samples) {
            if (s.xNorm() >= t0 && s.xNorm() <= t1) {
                sum += s.gapPx();
                n++;
            }
        }
        return n >= 3 ? sum / n : Double.NaN;
    }

    /** Угол клина: atan(|gapL − gapR| / (0.5 · widthRim)). */
    private static double estimateSkewDeg(double leftGapPx, double rightGapPx, int rimWidthPx) {
        double halfSpan = Math.max(40.0, rimWidthPx * 0.5);
        return Math.toDegrees(Math.atan(Math.abs(leftGapPx - rightGapPx) / halfSpan));
    }

    private static void release(Mat... mats) {
        for (Mat m : mats) {
            if (m != null) {
                m.release();
            }
        }
    }
}
