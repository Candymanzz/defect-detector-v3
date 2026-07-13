package com.example.iml.geometry.calibration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalibrationServiceTest {

    private final CalibrationService service = new CalibrationService();

    @Test
    void pixelsToMillimetersScalesByFactor() {
        assertEquals(2.5, service.pixelsToMillimeters(100, 0.025), 0.0001);
    }

    @Test
    void calibratePixelsToMmComputesRatio() {
        assertEquals(0.01, service.calibratePixelsToMm(1000, 10), 0.0001);
    }

    @Test
    void calibrateRejectsNonPositiveDistances() {
        assertThrows(IllegalArgumentException.class, () -> service.calibratePixelsToMm(0, 10));
        assertThrows(IllegalArgumentException.class, () -> service.calibratePixelsToMm(10, -1));
    }
}
