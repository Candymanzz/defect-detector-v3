package com.example.iml.orchestrator.integration.pipeline.roi;

import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Нормированный многоугольник области интереса [0,1]×[0,1] относительно полного кадра (как в analisSurface).
 */
public final class InterestPolygonNormCodec {

    private InterestPolygonNormCodec() {
    }

    public static List<Map<String, Object>> fromPixelRoi(PixelRoi roi, int frameWidth, int frameHeight) {
        if (roi == null || frameWidth <= 1 || frameHeight <= 1 || roi.width() <= 0 || roi.height() <= 0) {
            return List.of();
        }
        double dw = frameWidth - 1.0;
        double dh = frameHeight - 1.0;
        int x0 = roi.x();
        int y0 = roi.y();
        int x1 = roi.x() + roi.width() - 1;
        int y1 = roi.y() + roi.height() - 1;
        return List.of(
                point(x0 / dw, y0 / dh),
                point(x1 / dw, y0 / dh),
                point(x1 / dw, y1 / dh),
                point(x0 / dw, y1 / dh)
        );
    }

    public static List<Map<String, Object>> fromNormPoints(List<FpZoneNorm.PointNorm> points) {
        if (points == null || points.size() < 3) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(points.size());
        for (FpZoneNorm.PointNorm p : points) {
            out.add(point(p.x(), p.y()));
        }
        return out;
    }

    /**
     * Охватывающий прямоугольник полигона в пикселях (для {@code mainRoi}).
     */
    public static Map<String, Object> boundingPixelRoi(List<Map<String, Object>> polygonNorm, int frameWidth, int frameHeight) {
        if (polygonNorm == null || polygonNorm.size() < 3 || frameWidth <= 0 || frameHeight <= 0) {
            return null;
        }
        double dw = Math.max(1, frameWidth - 1);
        double dh = Math.max(1, frameHeight - 1);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> pt : polygonNorm) {
            double nx = toDouble(pt.get("x"));
            double ny = toDouble(pt.get("y"));
            if (Double.isNaN(nx) || Double.isNaN(ny)) {
                continue;
            }
            minX = Math.min(minX, nx);
            minY = Math.min(minY, ny);
            maxX = Math.max(maxX, nx);
            maxY = Math.max(maxY, ny);
        }
        if (!Double.isFinite(minX)) {
            return null;
        }
        int x = (int) Math.floor(minX * dw);
        int y = (int) Math.floor(minY * dh);
        int x2 = (int) Math.ceil(maxX * dw);
        int y2 = (int) Math.ceil(maxY * dh);
        x = clamp(x, 0, frameWidth - 1);
        y = clamp(y, 0, frameHeight - 1);
        x2 = clamp(x2, x, frameWidth - 1);
        y2 = clamp(y2, y, frameHeight - 1);
        int w = x2 - x + 1;
        int h = y2 - y + 1;
        if (w <= 0 || h <= 0) {
            return null;
        }
        Map<String, Object> roi = new LinkedHashMap<>();
        roi.put("x", x);
        roi.put("y", y);
        roi.put("width", w);
        roi.put("height", h);
        return roi;
    }

    private static Map<String, Object> point(double x, double y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
