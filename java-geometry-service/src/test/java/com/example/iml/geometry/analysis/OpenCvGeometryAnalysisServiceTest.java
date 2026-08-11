package com.example.iml.geometry.analysis;

import com.example.iml.geometry.calibration.CalibrationService;
import com.example.iml.geometry.codec.OpenCvImageCodec;
import com.example.iml.geometry.dto.InspectionRequest;
import com.example.iml.geometry.dto.InspectionResponse;
import com.example.iml.geometry.dto.NormPoint;
import com.example.iml.geometry.dto.RoiRect;
import com.example.iml.geometry.opencv.OpenCvNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCvGeometryAnalysisServiceTest {

    private static final int WIDTH = 2448;
    private static final int HEIGHT = 2048;

    @BeforeAll
    static void loadOpenCv() {
        OpenCvNativeLoader.ensureLoaded();
    }

    @Test
    void repeatedInspectWithPolygonDoesNotCorruptCachedReference() {
        Mat reference = createTexturedFrame();
        Mat current = reference.clone();
        OpenCvGeometryAnalysisService service = new OpenCvGeometryAnalysisService(
                new OpenCvImageCodec(),
                new CalibrationService(),
                false,
                true
        );
        InspectionRequest request = polygonInspectionRequest();
        try {
            service.inspectMats(reference, current, request, false);
            InspectionResponse second = service.inspectMats(reference, current, request, false);

            assertTrue(second.alignmentPass(), "alignment should pass on second inspect");
            assertTrue(Math.abs(second.shiftXmm()) < 100.0, "shiftXmm should not be fallback fail value");
            assertTrue(Math.abs(second.shiftYmm()) < 100.0, "shiftYmm should not be fallback fail value");
        } finally {
            reference.release();
            current.release();
        }
    }

    private static InspectionRequest polygonInspectionRequest() {
        List<NormPoint> polygon = List.of(
                new NormPoint(0.2, 0.2),
                new NormPoint(0.8, 0.2),
                new NormPoint(0.8, 0.8),
                new NormPoint(0.2, 0.8)
        );
        return new InspectionRequest(
                "",
                "",
                new RoiRect(0, 0, WIDTH, HEIGHT),
                polygon,
                null,
                null,
                null,
                0.01,
                0.5,
                1.0,
                0.2,
                0.3,
                0.25,
                "full",
                0.5,
                3.0,
                8.0,
                0.6,
                false,
                0.5
        );
    }

    private static Mat createTexturedFrame() {
        Mat frame = new Mat(HEIGHT, WIDTH, org.opencv.core.CvType.CV_8UC3, new Scalar(40, 40, 40));
        for (int y = 0; y < HEIGHT; y += 64) {
            Imgproc.line(frame, new Point(0, y), new Point(WIDTH, y), new Scalar(180, 180, 180), 2);
        }
        for (int x = 0; x < WIDTH; x += 64) {
            Imgproc.line(frame, new Point(x, 0), new Point(x, HEIGHT), new Scalar(120, 120, 120), 2);
        }
        for (int i = 0; i < 120; i++) {
            int x = 200 + (i * 37) % (WIDTH - 400);
            int y = 200 + (i * 53) % (HEIGHT - 400);
            int radius = 8 + (i % 5) * 4;
            Imgproc.circle(frame, new Point(x, y), radius, new Scalar(20 + i, 60, 200 - i), -1);
        }
        Core.add(frame, Mat.ones(frame.size(), frame.type()), frame);
        return frame;
    }
}
