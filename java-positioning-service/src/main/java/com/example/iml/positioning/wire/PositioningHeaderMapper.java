package com.example.iml.positioning.wire;

import com.example.iml.positioning.dto.NormPoint;
import com.example.iml.positioning.dto.PositioningRequest;
import com.example.iml.positioning.dto.PositioningResponse;
import com.example.iml.positioning.dto.PositioningTuning;
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
        PositioningTuning defaults = PositioningTuning.defaults();
        return new PositioningRequest(
                roiOrDefault(h.get("mainRoi")),
                polygonNormOrNull(h),
                num(h.get("pixelsToMm"), 0.02),
                num(h.get("maxShiftMm"), 0.5),
                num(h.get("maxRotationDeg"), 1.0),
                output,
                writeAligned,
                new PositioningTuning(
                        numOrNull(h.get("alignFailAbsdiff"), h.get("align_fail_absdiff"), defaults.alignFailAbsdiff()),
                        numOrNull(h.get("alignFailAbsdiffHard"), h.get("align_fail_absdiff_hard"), defaults.alignFailAbsdiffHard()),
                        numOrNull(h.get("alignFailResidualPx"), h.get("align_fail_residual_px"), defaults.alignFailResidualPx()),
                        numOrNull(h.get("eccSkipNcc"), h.get("ecc_skip_ncc"), defaults.eccSkipNcc()),
                        numOrNull(h.get("eccSkipAbsdiff"), h.get("ecc_skip_absdiff"), defaults.eccSkipAbsdiff()),
                        numOrNull(h.get("eccSkipResidualPx"), h.get("ecc_skip_residual_px"), defaults.eccSkipResidualPx())
                )
        );
    }

    public static Map<String, Object> toResponseHeader(PositioningResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", response.status());
        out.put("shiftXmm", jsonNum(response.shiftXmm()));
        out.put("shiftYmm", jsonNum(response.shiftYmm()));
        out.put("rotationDeg", jsonNum(response.rotationDeg()));
        out.put("homographyRefToCurrent", sanitizeHomography(response.homographyRefToCurrent()));
        out.put("alignmentPass", response.alignmentPass());
        out.put("overallPass", response.overallPass());
        out.put("alignedWritten", response.alignedWritten());
        out.put("output_shm_name", response.outputShmName());
        out.put("shm_name", response.outputShmName());
        out.put("shm_offset", 0);
        out.put("width", response.width());
        out.put("height", response.height());
        out.put("stride", response.stride());
        out.put("stage_ms_orb", jsonNum(response.stageMsOrb()));
        out.put("stage_ms_warp", jsonNum(response.stageMsWarp()));
        out.put("stage_ms_ecc", jsonNum(response.stageMsEcc()));
        out.put("stage_ms_write", jsonNum(response.stageMsWrite()));
        out.put("stage_ms_total", jsonNum(response.stageMsTotal()));
        if (response.diagnostics() != null && !response.diagnostics().isEmpty()) {
            // Flat keys only — nested Map is fine for Jackson, but keep one level for consumers.
            Map<String, Object> sanitizedDiag = sanitizeDiag(response.diagnostics());
            out.put("diagnostics", sanitizedDiag);
            for (Map.Entry<String, Object> e : sanitizedDiag.entrySet()) {
                String key = e.getKey();
                if ("status".equals(key) || "ref_cache_key".equals(key)) {
                    continue;
                }
                out.put("diag_" + key, e.getValue());
            }
        }
        return out;
    }

    /** Jackson cannot encode NaN/Inf — that turned every positioning RPC into MSG_ERROR. */
    private static Object jsonNum(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static double[] sanitizeHomography(double[] h) {
        if (h == null || h.length == 0) {
            return h == null ? new double[0] : h;
        }
        double[] out = new double[h.length];
        for (int i = 0; i < h.length; i++) {
            out[i] = Double.isFinite(h[i]) ? h[i] : 0.0;
        }
        return out;
    }

    private static Map<String, Object> sanitizeDiag(Map<String, Object> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>(diagnostics.size());
        for (Map.Entry<String, Object> e : diagnostics.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Double d && !Double.isFinite(d)) {
                out.put(e.getKey(), null);
            } else if (v instanceof Float f && !Float.isFinite(f)) {
                out.put(e.getKey(), null);
            } else {
                out.put(e.getKey(), v);
            }
        }
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

    private static double numOrNull(Object primary, Object secondary, double fallback) {
        if (primary != null) {
            return num(primary, fallback);
        }
        if (secondary != null) {
            return num(secondary, fallback);
        }
        return fallback;
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
