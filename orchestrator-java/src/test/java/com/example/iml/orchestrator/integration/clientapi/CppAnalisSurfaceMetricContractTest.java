package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CppAnalisSurfaceMetricContractTest {
    @Test
    void cameraIdentityIsSentAlongsideArbitraryProfileNames() throws Exception {
        var method=CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("putProfile",Map.class,Map.class,int.class);
        method.setAccessible(true);
        Map<String,Object> body=new LinkedHashMap<>();
        method.invoke(null,body,Map.of("analysis_profile","custom-blue-label"),4);
        assertEquals("custom-blue-label",body.get("analysis_profile"));
        assertEquals(4,body.get("camera_id"));
    }

    @Test
    void physicalDiagnosticsAndIndependentSeamRejectionReachThePipeline() throws Exception {
        var method=CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("inspectResponse",Map.class,Map.class);
        method.setAccessible(true);
        Map<String,Object> json=new LinkedHashMap<>();
        json.put("status","БРАК");json.put("anomaly_score",0.0);json.put("threshold",.6);
        json.put("reason","seam_not_found");json.put("shift_raw_mm",List.of(.1,.2));
        json.put("shift_limit_mm",List.of(.5,4.));json.put("mm_per_pixel",List.of(.27,.34));
        json.put("metric_scale_source","estimated");json.put("horizontal_shift_tolerance_mm",.5);
        json.put("vertical_shift_tolerance_mm",4.);json.put("seam",Map.of("found",false,"reject",true));
        var supervisor=new CppAnalisSurfaceHttpSupervisor("test","http://127.0.0.1:1",100);
        var result=(BinaryProtocol.Message)method.invoke(supervisor,Map.of("product_type","bucket"),json);
        assertEquals(false,result.header().get("ok"));
        for(String key:List.of("reason","shift_raw_mm","shift_limit_mm","mm_per_pixel","metric_scale_source",
                "horizontal_shift_tolerance_mm","vertical_shift_tolerance_mm","seam"))
            assertEquals(json.get(key),result.header().get(key),key);
        assertEquals(0.0,result.header().get("anomaly_score"));
    }

    @Test
    void absentUnknownAndInvalidMeasurementsCannotBecomeAccept() throws Exception {
        var method=CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("inspectResponse",Map.class,Map.class);
        method.setAccessible(true);
        var supervisor=new CppAnalisSurfaceHttpSupervisor("test","http://127.0.0.1:1",100);
        for(var response:List.of(Map.of(),Map.of("status","UNKNOWN"),Map.of("status","ГОДЕН"),
                Map.of("status","ГОДЕН","anomaly_score",Double.NaN,"threshold",.6),
                Map.of("status","ГОДЕН","anomaly_score",0,"threshold",0),
                Map.of("status","ГОДЕН","anomaly_score","0","threshold",.6))) {
            var result=(BinaryProtocol.Message)method.invoke(supervisor,Map.of(),response);
            assertEquals(BinaryProtocol.MSG_ERROR,result.type());
            assertEquals(false,result.header().get("ok"));
        }
        // Rounded score can equal the threshold while the native unrounded score is just below it.
        var valid=(BinaryProtocol.Message)method.invoke(supervisor,Map.of(),
                Map.of("status","ГОДЕН","anomaly_score",.6,"threshold",.6));
        assertEquals(BinaryProtocol.MSG_RESPONSE,valid.type());
        assertEquals(true,valid.header().get("ok"));
    }
}
