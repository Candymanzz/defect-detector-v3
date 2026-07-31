package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FastAPI хранит один эталон на {@code product_type}: загружаем активный ракурс из пакета и ROI.
 */
public final class ClientReferenceBundleSync {

    private final InspectShmHttpOps inspectOps;
    private final FpZonesHttpOps fpZones;

    public ClientReferenceBundleSync(InspectShmHttpOps inspectOps, FpZonesHttpOps fpZones) {
        this.inspectOps = inspectOps;
        this.fpZones = fpZones;
    }

    public BinaryProtocol.Message syncClientReferenceBundle(Map<String, Object> header) throws IOException {
        String productType = String.valueOf(header.get("product_type"));
        int activeIdx = Math.max(0, Math.min(3, YamlScalars.toInt(header.get("active_reference_view_index"), 0)));
        Map<String, Object> view = findViewByIndex(header.get("views"), activeIdx);
        if (view == null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "sync_client_reference_bundle: missing view index=" + activeIdx, "op", "sync_client_reference_bundle"),
                    new byte[0]
            );
        }
        int cameraId = YamlScalars.toInt(view.get("camera_id"), YamlScalars.toInt(header.get("camera_id"), -1));
        String scopedProductType = ReferenceRoiSignatureCache.scopedProductType(productType, cameraId);
        Map<String, Object> refHdr = new LinkedHashMap<>(view);
        refHdr.put("product_type", scopedProductType);
        refHdr.put("camera_id", cameraId);
        BinaryProtocol.Message refResp = inspectOps.uploadRefShm(refHdr);
        if (refResp.type() == BinaryProtocol.MSG_ERROR) {
            return refResp;
        }
        List<Map<String, Object>> points = findInterestPolygonNorm(header.get("interest_polygons_norm"), activeIdx);
        if (points == null || points.size() < 3) {
            Map<String, Object> interest = findInterestRoi(header.get("interest_rois"), activeIdx);
            if (interest != null) {
                int fw = YamlScalars.toInt(view.get("width"), 0);
                int fh = YamlScalars.toInt(view.get("height"), 0);
                if (fw > 1 && fh > 1) {
                    points = InterestPolygonNormCodec.fromPixelRoi(
                            new PixelRoi(
                                    YamlScalars.toInt(interest.get("x"), 0),
                                    YamlScalars.toInt(interest.get("y"), 0),
                                    YamlScalars.toInt(interest.get("width"), 0),
                                    YamlScalars.toInt(interest.get("height"), 0)
                            ),
                            fw,
                            fh
                    );
                }
            }
        }
        if (points != null && points.size() >= 3) {
            BinaryProtocol.Message roiResp = inspectOps.ensureRoiPolygon(
                    productType, cameraId, scopedProductType, points, header);
            if (roiResp != null && roiResp.type() == BinaryProtocol.MSG_ERROR) {
                return roiResp;
            }
        }
        Object fp = header.get("fp_zones");
        if (fp instanceof List<?> list) {
            List<?> cameraFpZones = filterFpZonesForCamera(list, cameraId);
            Map<String, Object> fpHdr = new LinkedHashMap<>(header);
            fpHdr.put("op", "replace_fp_zones");
            fpHdr.put("product_type", scopedProductType);
            fpHdr.put("camera_id", cameraId);
            fpHdr.put("fp_zones", cameraFpZones);
            BinaryProtocol.Message fpResp = fpZones.replaceFpZones(fpHdr);
            if (fpResp.type() == BinaryProtocol.MSG_ERROR) {
                return fpResp;
            }
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("product_type", productType);
        ok.put("active_reference_view_index", activeIdx);
        ok.put("transport", "http");
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }

    private static Map<String, Object> findViewByIndex(Object viewsObj, int index) {
        if (!(viewsObj instanceof List<?> views)) {
            return null;
        }
        for (Object o : views) {
            if (o instanceof Map<?, ?> m) {
                int vi = YamlScalars.toInt(m.get("view_index"), -1);
                if (vi == index) {
                    return YamlMaps.stringObjectMap(m);
                }
            }
        }
        if (index >= 0 && index < views.size() && views.get(index) instanceof Map<?, ?> m) {
            return YamlMaps.stringObjectMap(m);
        }
        return null;
    }

    private static List<Map<String, Object>> findInterestPolygonNorm(Object polysObj, int index) {
        if (!(polysObj instanceof List<?> polys)) {
            return null;
        }
        for (Object o : polys) {
            if (o instanceof Map<?, ?> m) {
                int vi = YamlScalars.toInt(m.get("view_index"), -1);
                if (vi == index) {
                    Object pts = m.get("points");
                    if (pts instanceof List<?> list) {
                        return ShmFramePayloadMapper.normalizeRoiPoints(list);
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Object> findInterestRoi(Object roisObj, int index) {
        if (!(roisObj instanceof List<?> rois)) {
            return null;
        }
        for (Object o : rois) {
            if (o instanceof Map<?, ?> m) {
                int vi = YamlScalars.toInt(m.get("view_index"), -1);
                if (vi == index) {
                    return YamlMaps.stringObjectMap(m);
                }
            }
        }
        return null;
    }

    private static List<?> filterFpZonesForCamera(List<?> zones, int cameraId) {
        List<Object> filtered = new ArrayList<>();
        for (Object zone : zones) {
            if (!(zone instanceof Map<?, ?> zoneMap)) {
                continue;
            }
            Object zoneCameraId = zoneMap.get("camera_id");
            if (zoneCameraId == null || YamlScalars.toInt(zoneCameraId, -1) == cameraId) {
                filtered.add(zone);
            }
        }
        return filtered;
    }
}
