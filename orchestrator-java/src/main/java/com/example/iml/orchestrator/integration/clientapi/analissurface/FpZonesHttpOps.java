package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replace false-positive zones via FastAPI /fp-zones. */
public final class FpZonesHttpOps {

    private final AnalisSurfaceHttpTransport http;

    public FpZonesHttpOps(AnalisSurfaceHttpTransport http) {
        this.http = http;
    }

    public BinaryProtocol.Message replaceFpZones(Map<String, Object> header) throws IOException {
        String productType = String.valueOf(header.get("product_type"));
        int hw = YamlScalars.toInt(header.get("heatmap_width"), 0);
        int hh = YamlScalars.toInt(header.get("heatmap_height"), 0);
        if (hw <= 0 || hh <= 0) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "replace_fp_zones: heatmap_width/height required", "op", "replace_fp_zones"),
                    new byte[0]
            );
        }
        HttpResponse<byte[]> listResp = http.httpGetRaw(
                "/fp-zones/" + AnalisSurfaceHttpTransport.urlEncodePathSegment(productType));
        if (listResp.statusCode() / 100 != 2) {
            return AnalisSurfaceHttpTransport.errorMessageToMsg(listResp, "fp-zones list");
        }
        Map<String, Object> listJson = AnalisSurfaceHttpTransport.readJson(listResp.body());
        Object zonesObj = listJson.get("zones");
        if (zonesObj instanceof List<?> zones) {
            for (Object z : zones) {
                if (z instanceof Map<?, ?> zm) {
                    Object id = zm.get("id");
                    if (id != null) {
                        http.httpDeleteRaw("/fp-zones/" + AnalisSurfaceHttpTransport.urlEncodePathSegment(String.valueOf(id)));
                    }
                }
            }
        }
        Object fp = header.get("fp_zones");
        if (!(fp instanceof List<?> fpList)) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "ok");
            ok.put("product_type", productType);
            ok.put("zones_count", 0);
            return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
        }
        int added = 0;
        for (Object o : fpList) {
            if (!(o instanceof Map<?, ?> zone)) {
                continue;
            }
            List<Map<String, Object>> pts = ShmFramePayloadMapper.heatmapPointsToRoiList(zone.get("points_norm_heatmap"));
            if (pts.size() < 3) {
                continue;
            }
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("product_type", productType);
            create.put("points", pts);
            create.put("heatmap_w", hw);
            create.put("heatmap_h", hh);
            Object note = zone.get("note");
            create.put("note", note == null ? "" : String.valueOf(note));
            HttpResponse<byte[]> cr = http.httpPostJson("/fp-zones", create);
            if (cr.statusCode() / 100 != 2) {
                return AnalisSurfaceHttpTransport.errorMessageToMsg(cr, "fp-zones create");
            }
            added++;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("product_type", productType);
        ok.put("zones_count", added);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }
}
