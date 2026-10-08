package com.example.iml.geometry.analysis;

import com.example.iml.geometry.opencv.OpenCvNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelRimSkewAnalyzerTest {

    @BeforeAll
    static void loadOpenCv() {
        OpenCvNativeLoader.ensureLoaded();
    }

    @Test
    void parallelGapIsInactiveOrLowAsymmetry() {
        Mat frame = syntheticBucket(false);
        try {
            LabelRimSkewAnalyzer.Result r = LabelRimSkewAnalyzer.analyze(frame, null, 0.02);
            assertTrue(r.active(), "yellow rim should be detected");
            assertTrue(r.gapAsymmetryMm() < 0.4, "asym=" + r.gapAsymmetryMm());
            assertTrue(r.skewDeg() < 2.0, "skew=" + r.skewDeg());
        } finally {
            frame.release();
        }
    }

    @Test
    void wedgeGapFailsThresholds() {
        Mat frame = syntheticBucket(true);
        try {
            LabelRimSkewAnalyzer.Result r = LabelRimSkewAnalyzer.analyze(frame, null, 0.02);
            assertTrue(r.active(), "yellow rim should be detected");
            assertTrue(
                    r.gapAsymmetryMm() > 0.8 || r.skewDeg() > 2.5,
                    "expected fat wedge, asym=" + r.gapAsymmetryMm() + " skew=" + r.skewDeg()
            );
        } finally {
            frame.release();
        }
    }

    @Test
    void realFrame40Cam5HasMeasurableSkew() {
        Path path = Path.of("D:/frame/camera_5/f_0000040/frame.jpg");
        if (!Files.isRegularFile(path)) {
            return;
        }
        Mat frame = Imgcodecs.imread(path.toString());
        assertFalse(frame.empty());
        try {
            LabelRimSkewAnalyzer.Result r = LabelRimSkewAnalyzer.analyze(frame, null, 0.02);
            assertTrue(r.active(), "yellow rim on cam5 #40");
            assertTrue(
                    r.gapAsymmetryMm() > 0.8 || r.skewDeg() > 2.5,
                    "insp#40 should exceed gates, asym=" + r.gapAsymmetryMm() + " skew=" + r.skewDeg()
            );
        } finally {
            frame.release();
        }
    }

    /** BGR synthetic: dark bg, red label body, yellow rim with optional wedge gap. */
    private static Mat syntheticBucket(boolean wedge) {
        int w = 400;
        int h = 300;
        Mat frame = new Mat(h, w, CvType.CV_8UC3, new Scalar(20, 20, 20));
        // Label body (bright red for gray edge vs dark gap)
        Imgproc.rectangle(frame, new Point(40, 40), new Point(360, 170), new Scalar(50, 50, 220), -1);
        // Yellow rim (BGR ~ 0,220,255)
        Scalar yellow = new Scalar(0, 220, 255);
        for (int x = 50; x < 350; x++) {
            double t = (x - 50) / 300.0;
            int gap = wedge ? (int) Math.round(8 + t * 70) : 18;
            int labelBottom = 170;
            int rimTop = labelBottom + gap;
            int rimBottom = Math.min(h - 10, rimTop + 40);
            Imgproc.rectangle(frame, new Point(x, rimTop), new Point(x + 1, rimBottom), yellow, -1);
        }
        // Dark gap already implied by background between label and rim
        return frame;
    }
}
