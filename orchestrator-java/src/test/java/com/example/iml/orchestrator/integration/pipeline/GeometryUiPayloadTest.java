package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GeometryUiPayloadTest {
    @Test void preservesMeasuredSeamBreakInFrameAndArchivePayload() {
        var response = new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of(
                "status","FAIL","jointPass",false,"joint_continuity_ran",true,
                "joint_continuity_pass",false,"joint_missing_run_mm",.4),new byte[0]);
        var payload = GeometryUiPayload.fromGeomResponse(response);
        assertEquals(false,payload.get("jointPass"));
        assertEquals(true,payload.get("joint_continuity_ran"));
        assertEquals(false,payload.get("joint_continuity_pass"));
        assertEquals(.4,payload.get("joint_missing_run_mm"));
    }
    @Test void preservesSkippedReasonWithoutInventingMeasuredContinuity() {
        var response = new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE,Map.of(
                "status","SKIPPED","overallPass",true,"error","geometry skipped: no joint ROI from client"),new byte[0]);
        var payload = GeometryUiPayload.fromGeomResponse(response);
        assertEquals("SKIPPED",payload.get("status"));
        assertTrue(String.valueOf(payload.get("error")).contains("no joint ROI"));
        assertFalse(payload.containsKey("joint_continuity_ran"));
    }
}
