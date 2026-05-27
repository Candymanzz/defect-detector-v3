package com.example.iml.geometry.analysis;

import com.example.iml.geometry.dto.NormPoint;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Маска многоугольника ROI в нормированных координатах кадра [0,1] (как analisSurface).
 */
public final class RoiPolygonMask {

    private RoiPolygonMask() {
    }

    public static List<NormPoint> validate(List<NormPoint> points) {
        if (points == null || points.size() < 3) {
            throw new IllegalArgumentException("ROI polygon must contain at least 3 points.");
        }
        for (int i = 0; i < points.size(); i++) {
            NormPoint p = points.get(i);
            if (p.x() < 0 || p.x() > 1 || p.y() < 0 || p.y() > 1) {
                throw new IllegalArgumentException("ROI polygon point #" + (i + 1) + " must be inside [0, 1].");
            }
        }
        return points;
    }

    public static Rect boundingRect(List<NormPoint> points, int frameWidth, int frameHeight) {
        validate(points);
        double dw = Math.max(1, frameWidth - 1);
        double dh = Math.max(1, frameHeight - 1);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (NormPoint p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        int x = clamp((int) Math.floor(minX * dw), 0, frameWidth - 1);
        int y = clamp((int) Math.floor(minY * dh), 0, frameHeight - 1);
        int x2 = clamp((int) Math.ceil(maxX * dw), x, frameWidth - 1);
        int y2 = clamp((int) Math.ceil(maxY * dh), y, frameHeight - 1);
        int w = x2 - x + 1;
        int h = y2 - y + 1;
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("ROI polygon bounding box is empty.");
        }
        return new Rect(x, y, w, h);
    }

    public static Mat maskForRect(List<NormPoint> points, Rect roiRect, int frameWidth, int frameHeight) {
        validate(points);
        Mat mask = Mat.zeros(roiRect.height, roiRect.width, org.opencv.core.CvType.CV_8UC1);
        MatOfPoint contour = new MatOfPoint();
        try {
            List<Point> local = new ArrayList<>(points.size());
            for (NormPoint p : points) {
                int px = normToPixelX(p.x(), frameWidth);
                int py = normToPixelY(p.y(), frameHeight);
                local.add(new Point(px - roiRect.x, py - roiRect.y));
            }
            contour.fromList(local);
            Imgproc.fillPoly(mask, List.of(contour), new Scalar(255));
            return mask;
        } finally {
            contour.release();
        }
    }

    public static void applyMask(Mat bgr, Mat mask8u) {
        if (mask8u == null) {
            return;
        }
        Core.bitwise_and(bgr, bgr, bgr, mask8u);
    }

    private static int normToPixelX(double nx, int frameWidth) {
        return (int) Math.round(nx * (frameWidth - 1));
    }

    private static int normToPixelY(double ny, int frameHeight) {
        return (int) Math.round(ny * (frameHeight - 1));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
