package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display metadata travels with the same frame decision, never with a global/latest cache. */
public final class LearningDiagnostics {
    private LearningDiagnostics() {}
    public static Map<String,Object> fromResponse(BinaryProtocol.Message response) {
        if(response==null || response.header()==null) return Map.of();
        Map<String,Object> values=new LinkedHashMap<>();
        for(String key:List.of("learning_engine","learning_robust_enabled","learning_diagnostics","learning_ms",
                "raw_anomaly_score","learned_normal_matches_count","learned_normal_adjustment","matched_accepted_case_ids")) {
            Object value=response.header().get(key);
            if(value!=null) values.put(key,value);
        }
        return Map.copyOf(values);
    }
}
