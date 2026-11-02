package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CppMetricSettingsTest {
    @Test
    void cameraIdentityIsSentAlongsideArbitraryProfileNames() throws Exception {
        var method = CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("putProfile", Map.class, Map.class, int.class);
        method.setAccessible(true);
        Map<String,Object> body = new LinkedHashMap<>();
        method.invoke(null, body, Map.of("analysis_profile", "custom-label"), 4);
        assertEquals("custom-label", body.get("analysis_profile"));
        assertEquals(4, body.get("camera_id"));
    }

    @Test
    void metricDiagnosticsReachTheInspectionPipeline() throws Exception {
        var method = CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("inspectResponse", Map.class, Map.class);
        method.setAccessible(true);
        Map<String,Object> json = new LinkedHashMap<>();
        json.put("status", "БРАК"); json.put("anomaly_score", .1); json.put("threshold", .6);
        json.put("reason", "shift_exceeded"); json.put("shift_mm", List.of(0., 0.));
        json.put("shift_raw_mm", List.of(2.2, .1)); json.put("shift_limit_mm", List.of(2., 4.));
        json.put("mm_per_pixel", List.of(.27, .34)); json.put("metric_scale_source", "estimated");
        json.put("horizontal_shift_tolerance_mm", 2.); json.put("vertical_shift_tolerance_mm", 4.);
        var supervisor = new CppAnalisSurfaceHttpSupervisor("test", "http://127.0.0.1:1", 100);
        var result = (BinaryProtocol.Message) method.invoke(supervisor, Map.of("product_type", "bucket"), json);
        assertEquals(false, result.header().get("ok"));
        for (String key : List.of("reason", "shift_mm", "shift_raw_mm", "shift_limit_mm", "mm_per_pixel",
                "metric_scale_source", "horizontal_shift_tolerance_mm", "vertical_shift_tolerance_mm"))
            assertEquals(json.get(key), result.header().get(key), key);
    }
}
