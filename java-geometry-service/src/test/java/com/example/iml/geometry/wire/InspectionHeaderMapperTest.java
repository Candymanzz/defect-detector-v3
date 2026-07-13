package com.example.iml.geometry.wire;

import com.example.iml.geometry.dto.InspectionRequest;
import com.example.iml.geometry.dto.NormPoint;
import com.example.iml.geometry.dto.RoiRect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionHeaderMapperTest {

    @Test
    void fromInspectCommandMapsFields() {
        Map<String, Object> header = Map.of(
                "referenceImageBase64", "ref",
                "currentImageBase64", "cur",
                "mainRoi", Map.of("x", 10, "y", 20, "width", 100, "height", 80),
                "main_roi_polygon_norm", List.of(
                        Map.of("x", 0.1, "y", 0.2),
                        Map.of("x", 0.9, "y", 0.2),
                        Map.of("x", 0.5, "y", 0.9)
                ),
                "pixelsToMm", 0.02,
                "maxShiftMm", 0.4,
                "maxRotationDeg", 2.0,
                "maxConcentricityMm", 0.3,
                "maxJointDefectMm", 0.5,
                "maxWrinklesScore", 0.15
        );

        InspectionRequest request = InspectionHeaderMapper.fromInspectCommand(header);

        assertEquals("ref", request.referenceImageBase64());
        assertEquals("cur", request.currentImageBase64());
        assertEquals(new RoiRect(10, 20, 100, 80), request.mainRoi());
        assertEquals(3, request.mainRoiPolygonNorm().size());
        assertEquals(0.02, request.pixelsToMm(), 0.0001);
        assertEquals(0.15, request.maxWrinklesScore(), 0.0001);
    }

    @Test
    void fromInspectShmMetadataUsesDefaultRoi() {
        InspectionRequest request = InspectionHeaderMapper.fromInspectShmMetadata(Map.of());

        assertEquals("", request.referenceImageBase64());
        assertEquals(new RoiRect(0, 0, 1224, 1024), request.mainRoi());
    }

    @Test
    void polygonNormAcceptsListPairs() {
        List<NormPoint> polygon = InspectionHeaderMapper.polygonNormOrNull(List.of(
                List.of(0.0, 0.0),
                List.of(1.0, 0.0),
                List.of(0.5, 1.0)
        ));

        assertNotNull(polygon);
        assertEquals(3, polygon.size());
        assertEquals(0.0, polygon.get(0).x(), 0.0001);
    }

    @Test
    void polygonNormRejectsTooFewPoints() {
        assertNull(InspectionHeaderMapper.polygonNormOrNull(List.of(Map.of("x", 0.1, "y", 0.2))));
    }

    @Test
    void numAndBoolHelpersHandleTypes() {
        assertEquals(3.5, InspectionHeaderMapper.num(3.5, 1.0));
        assertEquals(2.0, InspectionHeaderMapper.num("2", 1.0));
        assertEquals(1.0, InspectionHeaderMapper.num(null, 1.0));
        assertTrue(InspectionHeaderMapper.bool(true, false));
        assertTrue(InspectionHeaderMapper.bool("true", false));
    }
}
