package com.example.iml.geometry.analysis;

import com.example.iml.geometry.dto.NormPoint;
import com.example.iml.geometry.opencv.OpenCvNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Rect;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoiPolygonMaskTest {

    @BeforeAll
    static void loadOpenCv() {
        OpenCvNativeLoader.ensureLoaded();
    }

    @Test
    void validateRejectsOutOfRangePoint() {
        List<NormPoint> points = List.of(
                new NormPoint(0.0, 0.0),
                new NormPoint(1.2, 0.5),
                new NormPoint(0.5, 1.0)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> RoiPolygonMask.validate(points));
        assertTrue(ex.getMessage().contains("[0, 1]"));
    }

    @Test
    void boundingRectCoversNormalizedPolygon() {
        List<NormPoint> points = List.of(
                new NormPoint(0.1, 0.2),
                new NormPoint(0.8, 0.2),
                new NormPoint(0.8, 0.9),
                new NormPoint(0.1, 0.9)
        );

        Rect rect = RoiPolygonMask.boundingRect(points, 100, 80);

        assertTrue(rect.width > 0);
        assertTrue(rect.height > 0);
        assertEquals(9, rect.x);
        assertEquals(15, rect.y);
    }
}
