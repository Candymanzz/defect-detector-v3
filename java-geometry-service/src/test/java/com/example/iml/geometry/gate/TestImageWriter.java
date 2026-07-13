package com.example.iml.geometry.gate;

import com.example.iml.geometry.opencv.OpenCvNativeLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;

/** Generates gate input images under {@code testimage/}. */
public final class TestImageWriter {

    private TestImageWriter() {
    }

    public static void main(String[] args) throws Exception {
        OpenCvNativeLoader.ensureLoaded();
        Path dir = Path.of(args.length > 0 ? args[0] : "testimage");
        Files.createDirectories(dir);
        Mat reference = texturedFrame();
        Mat current = reference.clone();
        Imgproc.circle(current, new Point(1200, 1000), 40, new Scalar(0, 0, 255), -1);
        try {
            if (!Imgcodecs.imwrite(dir.resolve("ref.jpg").toString(), reference)) {
                throw new IllegalStateException("failed to write ref.jpg");
            }
            if (!Imgcodecs.imwrite(dir.resolve("cur.jpg").toString(), current)) {
                throw new IllegalStateException("failed to write cur.jpg");
            }
        } finally {
            reference.release();
            current.release();
        }
    }

    private static Mat texturedFrame() {
        int width = 2448;
        int height = 2048;
        Mat frame = new Mat(height, width, org.opencv.core.CvType.CV_8UC3, new Scalar(40, 40, 40));
        for (int y = 0; y < height; y += 64) {
            Imgproc.line(frame, new Point(0, y), new Point(width, y), new Scalar(180, 180, 180), 2);
        }
        for (int x = 0; x < width; x += 64) {
            Imgproc.line(frame, new Point(x, 0), new Point(x, height), new Scalar(120, 120, 120), 2);
        }
        for (int i = 0; i < 120; i++) {
            int x = 200 + (i * 37) % (width - 400);
            int y = 200 + (i * 53) % (height - 400);
            int radius = 8 + (i % 5) * 4;
            Imgproc.circle(frame, new Point(x, y), radius, new Scalar(20 + i, 60, 200 - i), -1);
        }
        Core.add(frame, Mat.ones(frame.size(), frame.type()), frame);
        return frame;
    }
}
