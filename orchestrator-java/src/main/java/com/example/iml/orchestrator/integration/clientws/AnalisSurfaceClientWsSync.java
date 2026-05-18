package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Заголовки команд analisSurface (Фаза 5): синхронизация пакета эталонов, FP, активного ракурса.
 */
public final class AnalisSurfaceClientWsSync {

    private AnalisSurfaceClientWsSync() {
    }

    public static Map<String, Object> syncClientReferenceBundle(ReferenceBundleSnapshot snap, int activeReferenceViewIndex) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("op", "sync_client_reference_bundle");
        h.put("product_type", snap.productType());
        h.put("heatmap_width", snap.heatmapWidth());
        h.put("heatmap_height", snap.heatmapHeight());
        h.put("active_reference_view_index", Math.max(0, Math.min(4, activeReferenceViewIndex)));
        h.put("fp_zones", fpZonesToJsonList(snap.fpZones()));
        List<Map<String, Object>> views = new ArrayList<>(5);
        List<Map<String, Object>> interestRois = new ArrayList<>(5);
        for (int i = 0; i < snap.views().size(); i++) {
            ReferenceViewSlot slot = snap.views().get(i);
            ShmFrameRefData f = slot.frame();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("view_index", i);
            v.put("camera_id", f.cameraId());
            v.put("frame_id", f.frameId());
            v.put("shm_name", f.shmName());
            v.put("shm_offset", f.shmOffset());
            v.put("width", f.width());
            v.put("height", f.height());
            v.put("stride", f.strideBytes());
            v.put("pixel_format", f.pixelFormat());
            v.put("channels", f.channels());
            if (f.expiresAtMs() != null) {
                v.put("expires_at_ms", f.expiresAtMs());
            }
            if (f.ttlMs() != null) {
                v.put("ttl_ms", f.ttlMs());
            }
            if (f.readToken() != null && !f.readToken().isBlank()) {
                v.put("read_token", f.readToken());
            }
            views.add(v);
            PixelRoi ir = slot.interestRoi();
            Map<String, Object> roi = new LinkedHashMap<>();
            roi.put("view_index", i);
            roi.put("x", ir.x());
            roi.put("y", ir.y());
            roi.put("width", ir.width());
            roi.put("height", ir.height());
            interestRois.add(roi);
        }
        h.put("views", views);
        h.put("interest_rois", interestRois);
        return h;
    }

    public static Map<String, Object> replaceFpZones(String productType, int heatmapW, int heatmapH, List<FpZoneNorm> zones) {
        Map<String, Object> h = new HashMap<>();
        h.put("op", "replace_fp_zones");
        h.put("product_type", productType);
        h.put("heatmap_width", heatmapW);
        h.put("heatmap_height", heatmapH);
        h.put("fp_zones", fpZonesToJsonList(zones));
        return h;
    }

    public static Map<String, Object> setActiveReferenceView(String productType, int viewIndex) {
        Map<String, Object> h = new HashMap<>();
        h.put("op", "set_active_reference_view");
        h.put("product_type", productType);
        h.put("view_index", Math.max(0, Math.min(4, viewIndex)));
        return h;
    }

    private static List<Map<String, Object>> fpZonesToJsonList(List<FpZoneNorm> zones) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FpZoneNorm z : zones) {
            Map<String, Object> zo = new LinkedHashMap<>();
            if (z.id() != null && !z.id().isBlank()) {
                zo.put("id", z.id());
            }
            zo.put("note", z.note() != null ? z.note() : "");
            List<Map<String, Object>> pts = new ArrayList<>();
            for (FpZoneNorm.PointNorm p : z.pointsNormHeatmap()) {
                Map<String, Object> po = new LinkedHashMap<>();
                po.put("x", p.x());
                po.put("y", p.y());
                pts.add(po);
            }
            zo.put("points_norm_heatmap", pts);
            out.add(zo);
        }
        return out;
    }
}
