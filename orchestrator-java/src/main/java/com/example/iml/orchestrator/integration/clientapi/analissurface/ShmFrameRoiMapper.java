package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ROI point normalization helpers. */
final class ShmFrameRoiMapper {

    private ShmFrameRoiMapper() {
    }

    static List<Map<String, Object>> normalizeRoiPoints(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                double x = YamlScalars.toDouble(m.get("x"), Double.NaN);
                double y = YamlScalars.toDouble(m.get("y"), Double.NaN);
                checkIsNan(out, x, y);
            } else if (o instanceof List<?> pair && pair.size() >= 2) {
                double x = YamlScalars.toDouble(pair.get(0), Double.NaN);
                double y = YamlScalars.toDouble(pair.get(1), Double.NaN);
                checkIsNan(out, x, y);
            }
        }
        return out;
    }

    private static void checkIsNan(List<Map<String, Object>> out, double x, double y) {
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("x", x);
            pt.put("y", y);
            out.add(pt);
        }
    }

    static List<Map<String, Object>> heatmapPointsToRoiList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                double x = YamlScalars.toDouble(m.get("x"), Double.NaN);
                double y = YamlScalars.toDouble(m.get("y"), Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y)) {
                    out.add(Map.of("x", x, "y", y));
                }
            }
        }
        return out;
    }
}
