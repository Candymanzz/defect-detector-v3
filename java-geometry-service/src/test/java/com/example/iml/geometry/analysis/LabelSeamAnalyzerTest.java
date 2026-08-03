package com.example.iml.geometry.analysis;

import com.example.iml.geometry.dto.NormPoint;
import com.example.iml.geometry.opencv.OpenCvNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.List;

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
            assertTrue(result.taperMm() < 0.15, "taperMm=" + result.taperMm());
            assertTrue(result.visibility() > 0.2, "visibility=" + result.visibility());
            assertEquals(0.0, result.defectMm(), 1e-6);
        } finally {
            roi.release();
        }
    }

    @Test
    void horizontalParallelSeamIsDetectedWithAxisHint() {
        double pixelsToMm = 0.02;
        int gapPx = 40;
        // Horizontal edges (0°), gap along vertical — typical bottom-view joint.
        Mat roi = syntheticSeam(240, 120, 0.0, gapPx);
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(
                    roi, null, pixelsToMm, 0.5, 3.0, 0.0);
            assertTrue(result.found(), "horizontal seam must be found with axis hint");
            assertTrue(result.parallelismDeg() < 2.0, "parallelismDeg=" + result.parallelismDeg());
            assertEquals(gapPx * pixelsToMm, result.widthMm(), 0.2);
        } finally {
            roi.release();
        }
    }

    @Test
    void verticalParallelSeamStillWorks() {
        double pixelsToMm = 0.02;
        int gapPx = 40;
        Mat roi = syntheticSeam(200, 160, 90.0, gapPx);
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(
                    roi, null, pixelsToMm, 0.5, 3.0, 90.0);
            assertTrue(result.found(), "vertical seam must be found");
            assertTrue(result.parallelismDeg() < 2.0, "parallelismDeg=" + result.parallelismDeg());
            assertEquals(gapPx * pixelsToMm, result.widthMm(), 0.2);
        } finally {
            roi.release();
        }
    }

    @Test
    void convergingSeamReportsTaperAndHigherParallelism() {
        Mat roi = syntheticConvergingSeam(220, 180, 8.0);
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, 0.02, 0.5, 5.0);
            assertTrue(result.found(), "expected two seam edges");
            assertTrue(result.parallelismDeg() > 4.0, "parallelismDeg=" + result.parallelismDeg());
            assertTrue(result.taperMm() > 0.05, "expected measurable taper, taperMm=" + result.taperMm());
            assertTrue(result.widthTopMm() != result.widthBottomMm() || result.taperMm() > 0.0);
        } finally {
            roi.release();
        }
    }

    @Test
    void horizontalConvergingSeamFailsParallelismGateBand() {
        Mat roi = syntheticConvergingSeamHorizontal(280, 140, 10.0);
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(
                    roi, null, 0.02, 0.25, 5.0, 0.0);
            assertTrue(result.found(), "expected horizontal converging edges");
            assertTrue(result.parallelismDeg() > 5.0, "parallelismDeg=" + result.parallelismDeg());
        } finally {
            roi.release();
        }
    }

    @Test
    void emptyRoiHasLowVisibility() {
        Mat roi = new Mat(120, 120, CvType.CV_8UC3, new Scalar(40, 40, 40));
        try {
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, 0.02, 0.5, 3.0);
            assertTrue(!result.found(), "empty ROI must not report a seam");
            assertTrue(result.visibility() < 0.2, "visibility=" + result.visibility());
        } finally {
            roi.release();
        }
    }

    @Test
    void cannyDoubleEdgeMicroGapIsReportedAsTooNarrowNotValidSeam() {
        Mat roi = new Mat(160, 200, CvType.CV_8UC3, new Scalar(30, 30, 30));
        try {
            Imgproc.line(roi, new Point(100, 10), new Point(100, 150), new Scalar(220, 220, 220), 4);
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, 0.02, 0.5, 3.0);
            if (result.found()) {
                assertTrue(
                        result.widthMm() < 0.5,
                        "double-edge micro gap must stay below min width, widthMm=" + result.widthMm()
                );
                assertTrue(result.defectMm() > 0.0, "must flag defect vs joint_min_width");
            } else {
                assertEquals(9999.0, result.defectMm(), 1e-6);
            }
        } finally {
            roi.release();
        }
    }

    @Test
    void realSeamPreferredOverNearbyDoubleEdge() {
        double pixelsToMm = 0.02;
        int gapPx = 40;
        Mat roi = syntheticSeam(200, 160, 0.0, gapPx);
        try {
            Imgproc.line(roi, new Point(20, 20), new Point(20, 140), new Scalar(220, 220, 220), 5);
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, pixelsToMm, 0.5, 3.0);
            assertTrue(result.found(), "expected real seam edges");
            assertTrue(
                    result.widthMm() >= 0.5 && result.widthMm() <= 3.0,
                    "width must stay in seam band, got " + result.widthMm()
            );
            assertTrue(result.widthMm() > 0.25, "must not pick micro double-edge");
            assertEquals(0.0, result.defectMm(), 1e-6);
        } finally {
            roi.release();
        }
    }

    @Test
    void polygonMaskSuppressesOutsideEdges() {
        double pixelsToMm = 0.02;
        int gapPx = 40;
        Mat roi = syntheticSeam(200, 160, 0.0, gapPx);
        Mat mask = Mat.zeros(160, 200, CvType.CV_8UC1);
        try {
            // Distract with a second parallel pair on the left.
            Imgproc.line(roi, new Point(30, 10), new Point(30, 150), new Scalar(220, 220, 220), 2);
            Imgproc.line(roi, new Point(70, 10), new Point(70, 150), new Scalar(220, 220, 220), 2);
            // Keep only the center seam band.
            Imgproc.rectangle(mask, new Point(70, 0), new Point(130, 160), new Scalar(255), -1);
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(roi, mask, pixelsToMm, 0.5, 3.0);
            assertTrue(result.found(), "masked center seam must be found");
            assertEquals(gapPx * pixelsToMm, result.widthMm(), 0.2);
        } finally {
            roi.release();
            mask.release();
        }
    }

    @Test
    void segmenterFindsHorizontalBrightBandAndLowParallelism() {
        Mat roi = new Mat(100, 240, CvType.CV_8UC3, new Scalar(30, 30, 30));
        try {
            // Bright horizontal seam band (gap between two dark regions).
            Imgproc.rectangle(roi, new Point(10, 40), new Point(230, 60), new Scalar(220, 220, 220), -1);
            LabelSeamAnalyzer.Result result = LabelSeamBandSegmenter.analyze(
                    roi, null, 0.02, 0.25, 3.0, 0.0);
            assertTrue(result.found(), "segmented band must be found");
            assertTrue(result.parallelismDeg() < 3.0, "parallelismDeg=" + result.parallelismDeg());
            assertTrue(result.widthMm() > 0.2, "widthMm=" + result.widthMm());
        } finally {
            roi.release();
        }
    }

    @Test
    void analyzeWithSegmentationFlagUsesBandWhenPossible() {
        Mat roi = new Mat(100, 240, CvType.CV_8UC3, new Scalar(30, 30, 30));
        try {
            Imgproc.rectangle(roi, new Point(10, 40), new Point(230, 60), new Scalar(220, 220, 220), -1);
            LabelSeamAnalyzer.Result result = LabelSeamAnalyzer.analyze(
                    roi, null, 0.02, 0.25, 3.0, 0.0, true);
            assertTrue(result.found(), "enabled segmentation must find band");
            assertTrue(result.parallelismDeg() < 3.0, "parallelismDeg=" + result.parallelismDeg());
        } finally {
            roi.release();
        }
    }

    @Test
    void estimateAxisDegFromHorizontalOrientedRect() {
        // Oriented rect: long sides horizontal (y≈0.4 and y≈0.6), short vertical.
        List<NormPoint> poly = List.of(
                new NormPoint(0.2, 0.4),
                new NormPoint(0.8, 0.4),
                new NormPoint(0.8, 0.6),
                new NormPoint(0.2, 0.6)
        );
        Rect roi = new Rect(0, 0, 100, 100);
        double axis = LabelSeamAnalyzer.estimateAxisDegFromPolygon(poly, roi, 100, 100);
        assertTrue(!Double.isNaN(axis), "axis must be estimated");
        assertTrue(
                LabelSeamAnalyzer.smallestAngleDiffDeg(axis, 0.0) < 5.0
                        || LabelSeamAnalyzer.smallestAngleDiffDeg(axis, 180.0) < 5.0,
                "expected near-horizontal axis, got " + axis
        );
    }

    @Test
    void smallestAngleDiffHandlesWrapAround() {
        assertEquals(2.0, LabelSeamAnalyzer.smallestAngleDiffDeg(1.0, 179.0), 1e-9);
        assertEquals(10.0, LabelSeamAnalyzer.smallestAngleDiffDeg(5.0, 15.0), 1e-9);
    }

    private static Mat syntheticSeam(int width, int height, double tiltDeg, int gapPx) {
        Mat roi = new Mat(height, width, CvType.CV_8UC3, new Scalar(30, 30, 30));
        double cx = width / 2.0;
        double cy = height / 2.0;
        double half = gapPx / 2.0;
        // Gap is perpendicular to the seam-edge direction (tiltDeg: 0=horizontal, 90=vertical).
        double rad = Math.toRadians(tiltDeg);
        double nx = -Math.sin(rad);
        double ny = Math.cos(rad);
        drawTiltedLine(roi, cx - nx * half, cy - ny * half, tiltDeg, width, height);
        drawTiltedLine(roi, cx + nx * half, cy + ny * half, tiltDeg, width, height);
        return roi;
    }

    private static Mat syntheticConvergingSeam(int width, int height, double angleDiffDeg) {
        Mat roi = new Mat(height, width, CvType.CV_8UC3, new Scalar(30, 30, 30));
        double half = angleDiffDeg / 2.0;
        double gap = 30.0;
        // Near-vertical edges that converge (legacy vertical joint case).
        drawTiltedLine(roi, width * 0.5 - gap / 2.0, height / 2.0, 90.0 - half, width, height);
        drawTiltedLine(roi, width * 0.5 + gap / 2.0, height / 2.0, 90.0 + half, width, height);
        return roi;
    }

    /** Two near-horizontal edges that diverge (not parallel). */
    private static Mat syntheticConvergingSeamHorizontal(int width, int height, double angleDiffDeg) {
        Mat roi = new Mat(height, width, CvType.CV_8UC3, new Scalar(30, 30, 30));
        double half = angleDiffDeg / 2.0;
        double gap = 36.0;
        drawTiltedLine(roi, width / 2.0, height / 2.0 - gap / 2.0, 0.0 + half, width, height);
        drawTiltedLine(roi, width / 2.0, height / 2.0 + gap / 2.0, 0.0 - half, width, height);
        return roi;
    }

    private static void drawTiltedLine(Mat roi, double cx, double cy, double tiltDeg, int width, int height) {
        double rad = Math.toRadians(tiltDeg);
        // Edge direction along tilt (0° = horizontal to the right).
        double dx = Math.cos(rad);
        double dy = Math.sin(rad);
        double len = Math.max(width, height);
        Point p1 = new Point(cx - dx * len, cy - dy * len);
        Point p2 = new Point(cx + dx * len, cy + dy * len);
        Imgproc.line(roi, p1, p2, new Scalar(220, 220, 220), 2);
    }
}
