package com.example.iml.geometry.wire;

import com.example.iml.geometry.dto.InspectionRequest;
import com.example.iml.geometry.dto.RoiRect;

import java.util.Map;

public final class InspectionHeaderMapper {

    private InspectionHeaderMapper() {
    }

    public static InspectionRequest fromInspectCommand(Map<String, Object> h) {
        return new InspectionRequest(
                str(h.get("referenceImageBase64")),
                str(h.get("currentImageBase64")),
                roi(h.get("mainRoi")),
                roiOrNull(h.get("jointRoi")),
                roiOrNull(h.get("wrinklesRoi")),
                num(h.get("pixelsToMm"), 0.01),
                num(h.get("maxShiftMm"), 0.5),
                num(h.get("maxRotationDeg"), 1.0),
                num(h.get("maxConcentricityMm"), 0.2),
                num(h.get("maxJointDefectMm"), 0.3),
                num(h.get("maxWrinklesScore"), 0.05)
        );
    }

    public static InspectionRequest fromInspectShmMetadata(Map<String, Object> h) {
        return new InspectionRequest(
                "",
                "",
                roiOrDefault(h.get("mainRoi")),
                roiOrNull(h.get("jointRoi")),
                roiOrNull(h.get("wrinklesRoi")),
                num(h.get("pixelsToMm"), 0.01),
                num(h.get("maxShiftMm"), 0.5),
                num(h.get("maxRotationDeg"), 1.0),
                num(h.get("maxConcentricityMm"), 0.2),
                num(h.get("maxJointDefectMm"), 0.3),
                num(h.get("maxWrinklesScore"), 0.05)
        );
    }

    @SuppressWarnings("unchecked")
    public static RoiRect roi(Object o) {
        Map<String, Object> m = (Map<String, Object>) o;
        return new RoiRect(
                (int) num(m.get("x"), 0),
                (int) num(m.get("y"), 0),
                (int) num(m.get("width"), 1),
                (int) num(m.get("height"), 1)
        );
    }

    public static RoiRect roiOrNull(Object o) {
        if (o == null) {
            return null;
        }
        return roi(o);
    }

    public static RoiRect roiOrDefault(Object o) {
        if (o == null) {
            return new RoiRect(0, 0, 2448, 2048);
        }
        return roi(o);
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
