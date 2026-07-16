package com.example.iml.geometry.analysis;

import com.example.iml.geometry.opencv.OpenCvNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelSeamAnalyzerTest {

    @BeforeAll
    static void loadOpenCv() {
        OpenCvNativeLoader.ensureLoaded();
    }

    @Test
    void parallelSeamReportsLowParallelismAndExpectedWidth() {
        double pixelsToMm = 0.02;
        int gapPx = 40;
        Mat roi = syntheticSeam(200, 160, 0.0, gapPx);
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, pixelsToMm, 0.5, 3.0);
            assertTrue(result.found(), "expected two seam edges");
            assertTrue(result.parallelismDeg() < 2.0, "parallelismDeg=" + result.parallelismDeg());
            double expectedWidthMm = gapPx * pixelsToMm;
            assertEquals(expectedWidthMm, result.widthMm(), 0.15);
            assertTrue(result.visibility() > 0.2, "visibility=" + result.visibility());
            assertEquals(0.0, result.defectMm(), 1e-6);
        } finally {
            roi.release();
        }
    }

    @Test
    void convergingSeamReportsHigherParallelism() {
        Mat roi = syntheticConvergingSeam(220, 180, 8.0);
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, 0.02, 0.5, 5.0);
            assertTrue(result.found(), "expected two seam edges");
            assertTrue(result.parallelismDeg() > 4.0, "parallelismDeg=" + result.parallelismDeg());
        } finally {
            roi.release();
        }
    }

    @Test
    void emptyRoiHasLowVisibility() {
        Mat roi = new Mat(120, 120, CvType.CV_8UC3, new Scalar(40, 40, 40));
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, 0.02, 0.5, 3.0);
            assertTrue(result.visibility() < 0.2, "visibility=" + result.visibility());
        } finally {
            roi.release();
        }
    }

    @Test
    void smallestAngleDiffHandlesWrapAround() {
        assertEquals(2.0, LabelSeamAnalyzer.smallestAngleDiffDeg(1.0, 179.0), 1e-9);
        assertEquals(10.0, LabelSeamAnalyzer.smallestAngleDiffDeg(5.0, 15.0), 1e-9);
    }

    private static Mat syntheticSeam(int width, int height, double tiltDeg, int gapPx) {
        Mat roi = new Mat(height, width, CvType.CV_8UC3, new Scalar(30, 30, 30));
        double rad = Math.toRadians(tiltDeg);
        double cx = width / 2.0;
        double cy = height / 2.0;
        double half = gapPx / 2.0;
        drawTiltedLine(roi, cx - half, cy, rad, width, height);
        drawTiltedLine(roi, cx + half, cy, rad, width, height);
        return roi;
    }

    private static Mat syntheticConvergingSeam(int width, int height, double angleDiffDeg) {
        Mat roi = new Mat(height, width, CvType.CV_8UC3, new Scalar(30, 30, 30));
        double half = angleDiffDeg / 2.0;
        drawTiltedLine(roi, width * 0.42, height / 2.0, -half, width, height);
        drawTiltedLine(roi, width * 0.58, height / 2.0, half, width, height);
        return roi;
    }

    private static void drawTiltedLine(Mat roi, double cx, double cy, double tiltDeg, int width, int height) {
        double rad = Math.toRadians(tiltDeg);
        double dx = Math.sin(rad);
        double dy = Math.cos(rad);
        double len = Math.max(width, height);
        Point p1 = new Point(cx - dx * len, cy - dy * len);
        Point p2 = new Point(cx + dx * len, cy + dy * len);
        Imgproc.line(roi, p1, p2, new Scalar(220, 220, 220), 2);
    }
}
