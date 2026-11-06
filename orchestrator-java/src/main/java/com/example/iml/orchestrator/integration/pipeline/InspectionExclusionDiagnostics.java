package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable configuration snapshot belonging to one inspected frame. */
public final class InspectionExclusionDiagnostics {
    private InspectionExclusionDiagnostics() {}

    public static Map<String, Object> fromResponse(BinaryProtocol.Message response) {
        return response == null || response.header() == null ? Map.of()
                : snapshot(response.header().get("inspection_exclusions"));
    }

    public static Map<String, Object> snapshot(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String name && item != null) result.put(name, immutable(item));
        });
        return Map.copyOf(result);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?>) return snapshot(value);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) if (item != null) copy.add(immutable(item));
            return List.copyOf(copy);
        }
        return value;
    }
}
