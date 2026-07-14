package com.example.iml.positioning.wire;

import com.example.iml.positioning.dto.NormPoint;
import com.example.iml.positioning.dto.PositioningRequest;
import com.example.iml.positioning.dto.PositioningResponse;
import com.example.iml.positioning.dto.RoiRect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PositioningHeaderMapper {

    private PositioningHeaderMapper() {
    }

    public static PositioningRequest fromCommand(Map<String, Object> h) {
        String output = str(h.get("output_shm_name"));
        if (output.isBlank()) {
            output = str(h.get("outputShmName"));
        }
        boolean writeAligned = bool(h.get("write_aligned"), true);
        if (h.containsKey("writeAligned")) {
            writeAligned = bool(h.get("writeAligned"), writeAligned);
        }
        return new PositioningRequest(
                roiOrDefault(h.get("mainRoi")),
                polygonNormOrNull(h),
                num(h.get("pixelsToMm"), 0.02),
                num(h.get("maxShiftMm"), 0.5),
                num(h.get("maxRotationDeg"), 1.0),
                output,
                writeAligned
        );
    }

    public static Map<String, Object> toResponseHeader(PositioningResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", response.status());
        out.put("shiftXmm", response.shiftXmm());
        out.put("shiftYmm", response.shiftYmm());
        out.put("rotationDeg", response.rotationDeg());
        out.put("homographyRefToCurrent", response.homographyRefToCurrent());
        out.put("alignmentPass", response.alignmentPass());
        out.put("overallPass", response.overallPass());
        out.put("alignedWritten", response.alignedWritten());
        out.put("output_shm_name", response.outputShmName());
        out.put("shm_name", response.outputShmName());
        out.put("shm_offset", 0);
        out.put("width", response.width());
        out.put("height", response.height());
        out.put("stride", response.stride());
        return out;
    }

    public static RoiRect roiOrDefault(Object o) {
        if (o == null) {
            return new RoiRect(0, 0, 1224, 1024);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return new RoiRect(
                (int) num(m.get("x"), 0),
                (int) num(m.get("y"), 0),
                (int) num(m.get("width"), 1),
                (int) num(m.get("height"), 1)
        );
    }

    public static List<NormPoint> polygonNormOrNull(Map<String, Object> h) {
        Object raw = h.get("mainRoiPolygonNorm");
        if (raw == null) {
            raw = h.get("main_roi_polygon_norm");
        }
        if (raw == null) {
            raw = h.get("roi_polygon_norm");
        }
        if (raw == null) {
            raw = h.get("interest_polygon_norm");
        }
        if (!(raw instanceof List<?> list) || list.size() < 3) {
            return null;
        }
        List<NormPoint> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(new NormPoint(num(m.get("x"), Double.NaN), num(m.get("y"), Double.NaN)));
            } else if (o instanceof List<?> pair && pair.size() >= 2) {
                out.add(new NormPoint(num(pair.get(0), Double.NaN), num(pair.get(1), Double.NaN)));
            }
        }
        return out.size() >= 3 ? out : null;
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static double num(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return fallback;
        }
        return Double.parseDouble(String.valueOf(o));
    }

    public static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
