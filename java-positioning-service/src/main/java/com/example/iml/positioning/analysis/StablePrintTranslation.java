package com.example.iml.positioning.analysis;

import com.example.iml.positioning.dto.NormPoint;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded rigid correction supported by independent interior print patches. */
final class StablePrintTranslation {
    record Estimate(double dx, double dy, int supporters) {}
    private record Vote(double dx, double dy, int row, int col) {}
    private static final double MAX_SHIFT_PX = 4.0;

    static Estimate estimate(Mat reference, Mat current, Rect roi, List<NormPoint> polygon) {
        Mat refGray = new Mat(), curGray = new Mat(), ref = new Mat(), cur = new Mat();
        Mat refRoi = null, curRoi = null, mask = null, smallMask = new Mat();
        List<Vote> votes = new ArrayList<>();
        try {
            Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(current, curGray, Imgproc.COLOR_BGR2GRAY);
            refRoi = new Mat(refGray, roi); curRoi = new Mat(curGray, roi);
            double scale = Math.min(1.0, 640.0 / Math.max(roi.width, roi.height));
            Imgproc.resize(refRoi, ref, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.resize(curRoi, cur, ref.size(), 0, 0, Imgproc.INTER_AREA);
            // Use the actual rounded resize scale for transformation units.
            double sx = ref.cols() / (double) roi.width, sy = ref.rows() / (double) roi.height;
            int marginX = (int) Math.ceil(MAX_SHIFT_PX * sx), marginY = (int) Math.ceil(MAX_SHIFT_PX * sy);
            if (ref.cols() < 80 || ref.rows() < 80) return null;
            if (polygon != null && polygon.size() >= 3) {
                mask = RoiPolygonMask.maskForRect(polygon, roi, reference.cols(), reference.rows());
                Imgproc.resize(mask, smallMask, ref.size(), 0, 0, Imgproc.INTER_NEAREST);
            }
            // Leave the perimeter/seam out. Several patches must agree across
            // both axes; a damaged patch, flat surface or ambiguous print abstains.
            int w = Math.max(16, ref.cols() / 7), h = Math.max(16, ref.rows() / 6);
            for (int row = 0; row < 3; row++) for (int col = 0; col < 4; col++) {
                int x = (int) Math.round(ref.cols() * (0.23 + col * 0.18)) - w / 2;
                int y = (int) Math.round(ref.rows() * (0.25 + row * 0.25)) - h / 2;
                if (x < marginX || y < marginY || x + w + marginX > ref.cols() || y + h + marginY > ref.rows()) continue;
                Rect patch = new Rect(x, y, w, h);
                Mat template = new Mat(ref, patch), search = null, result = new Mat();
                MatOfDouble mean = new MatOfDouble(), std = new MatOfDouble();
                Mat patchMask = null;
                try {
                    if (!smallMask.empty()) {
                        patchMask = new Mat(smallMask, new Rect(x - marginX, y - marginY, w + 2 * marginX, h + 2 * marginY));
                        if (Core.countNonZero(patchMask) != patchMask.total()) continue;
                    }
                    Core.meanStdDev(template, mean, std);
                    if (std.toArray()[0] < 10.0) continue;
                    search = new Mat(cur, new Rect(x - marginX, y - marginY, w + 2 * marginX, h + 2 * marginY));
                    Imgproc.matchTemplate(search, template, result, Imgproc.TM_CCOEFF_NORMED);
                    Core.MinMaxLocResult peak = Core.minMaxLoc(result);
                    int px = (int) peak.maxLoc.x, py = (int) peak.maxLoc.y;
                    if (peak.maxVal < 0.85 || peak.maxVal - Core.mean(result).val[0] < 0.01
                            || px == 0 || py == 0 || px == result.cols() - 1 || py == result.rows() - 1) continue;
                    double dx = (px + peakOffset(result.get(py, px - 1)[0], peak.maxVal, result.get(py, px + 1)[0]) - marginX) / sx;
                    double dy = (py + peakOffset(result.get(py - 1, px)[0], peak.maxVal, result.get(py + 1, px)[0]) - marginY) / sy;
                    if (Math.hypot(dx, dy) <= MAX_SHIFT_PX) votes.add(new Vote(dx, dy, row, col));
                } finally {
                    release(template, search, result, mean, std, patchMask);
                }
            }
            if (votes.size() < 4) return null;
            final double medianX = median(votes.stream().map(Vote::dx).toList()), medianY = median(votes.stream().map(Vote::dy).toList());
            List<Vote> consensus = votes.stream().filter(v -> Math.hypot(v.dx - medianX, v.dy - medianY) <= 0.65).toList();
            if (consensus.size() < 4 || consensus.size() * 2 < votes.size()
                    || consensus.stream().map(Vote::row).distinct().count() < 2
                    || consensus.stream().map(Vote::col).distinct().count() < 2) return null;
            double dx = median(consensus.stream().map(Vote::dx).toList()), dy = median(consensus.stream().map(Vote::dy).toList());
            if (Math.hypot(dx, dy) < 0.35) return null;
            // matchTemplate locates reference in current: alignment uses the inverse.
            return new Estimate(-dx, -dy, consensus.size());
        } finally {
            release(refGray, curGray, ref, cur, refRoi, curRoi, mask, smallMask);
        }
    }

    private static double peakOffset(double left, double peak, double right) {
        double denominator = left - 2.0 * peak + right;
        return denominator < -1e-6 ? Math.max(-0.5, Math.min(0.5, 0.5 * (left - right) / denominator)) : 0.0;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values); sorted.sort(Comparator.naturalOrder());
        int n = sorted.size(); return (sorted.get((n - 1) / 2) + sorted.get(n / 2)) * 0.5;
    }
    private static void release(Mat... mats) { for (Mat mat : mats) if (mat != null) mat.release(); }
}
