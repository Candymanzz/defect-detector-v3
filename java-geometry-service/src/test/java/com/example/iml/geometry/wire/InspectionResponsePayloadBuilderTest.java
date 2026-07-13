package com.example.iml.geometry.wire;

import com.example.iml.geometry.dto.InspectionResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class InspectionResponsePayloadBuilderTest {

    @Test
    void toResponseHeaderMapsCoreFields() {
        InspectionResponse response = new InspectionResponse(
                0.1,
                0.2,
                0.3,
                new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1},
                0.05,
                0.06,
                0.07,
                true,
                true,
                false,
                true,
                true,
                "debug-b64",
                "PASS"
        );

        Map<String, Object> header = InspectionResponsePayloadBuilder.toResponseHeader(response, false);

        assertEquals(0.1, header.get("shiftXmm"));
        assertEquals("PASS", header.get("status"));
        assertEquals(true, header.get("overallPass"));
        assertFalse(header.containsKey("debugImageBase64"));
    }

    @Test
    void includeDebugAddsDebugImage() {
        InspectionResponse response = new InspectionResponse(
                0, 0, 0, new double[9], 0, 0, 0,
                true, true, true, true, true,
                "img",
                "PASS"
        );

        Map<String, Object> header = InspectionResponsePayloadBuilder.toResponseHeader(response, true);

        assertEquals("img", header.get("debugImageBase64"));
        assertNull(header.get("missing"));
    }
}
