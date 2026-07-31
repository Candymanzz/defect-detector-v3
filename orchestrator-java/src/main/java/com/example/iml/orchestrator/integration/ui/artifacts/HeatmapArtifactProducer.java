package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Generate / resolve heatmap U8 artifacts for UI publish. */
public final class HeatmapArtifactProducer {

    private final Logger log;

    public HeatmapArtifactProducer(Logger log) {
        this.log = log;
    }

    public HeatmapArtifact generateHeatmapArtifact(
            BinaryRpcSupervisor uiVisualsPython,
            ReferenceSnapshot activeReference,
            BinaryProtocol.Message geometry,
            Map<String, Object> uiCfg,
            int cameraId,
            long frameId,
            String productType,
            String detectorId,
            FrozenFrame frozenFrame,
            int width,
            int height,
            int stride
    ) {
        if (uiVisualsPython == null) {
            return HeatmapArtifact.empty();
        }
        if (activeReference == null || activeReference.header() == null
                || activeReference.header().get("shm_name") == null) {
            log.debug("ui heatmap skipped cam={} frame={} reason=reference_not_synced", cameraId, frameId);
            return HeatmapArtifact.empty();
        }
        try {
            Map<String, Object> pyHeader = buildInspectHeader(
                    activeReference, geometry, uiCfg, cameraId, frameId,
                    productType, detectorId, frozenFrame, width, height, stride);
            Path heatmapOutRequested = FrameJpegWriter.imlShmFilePath("iml_ui_heatmap_cam_" + cameraId);
            pyHeader.put("heatmap_u8_output_path", heatmapOutRequested.toString());
            BinaryProtocol.Message heatmapResp = uiVisualsPython.command(pyHeader);
            if (heatmapResp.type() == BinaryProtocol.MSG_ERROR) {
                log.warn(
                        "ui heatmap generation failed cam={} frame={} error={}",
                        cameraId,
                        frameId,
                        heatmapResp.header() == null ? "unknown" : heatmapResp.header().get("error")
                );
                return HeatmapArtifact.empty();
            }
            return resolveHeatmapArtifact(heatmapResp.header(), heatmapOutRequested, width, height);
        } catch (Exception e) {
            log.warn("ui heatmap generation failed cam={} frame={}: {}", cameraId, frameId, e.getMessage());
            return HeatmapArtifact.empty();
        }
    }

    /**
     * Ожидается, что Python в заголовке ответа передаёт путь к записанному heatmap (и при необходимости размеры).
     * Поддерживаются несколько имён полей; если путь в JSON отсутствует, берётся файл по
     * {@code heatmap_u8_output_path} из запроса (тот же путь, куда пишет воркер).
     */
    public HeatmapArtifact resolveHeatmapArtifact(
            Map<String, Object> respHeader,
            Path requestedOutputPath,
            int captureWidth,
            int captureHeight
    ) {
        return HeatmapArtifactResolver.resolve(respHeader, requestedOutputPath, captureWidth, captureHeight);
    }

    private static Map<String, Object> buildInspectHeader(
            ReferenceSnapshot activeReference,
            BinaryProtocol.Message geometry,
            Map<String, Object> uiCfg,
            int cameraId,
            long frameId,
            String productType,
            String detectorId,
            FrozenFrame frozenFrame,
            int width,
            int height,
            int stride
    ) {
        Map<String, Object> pyHeader = new HashMap<>();
        pyHeader.put("op", "inspect_shm");
        pyHeader.put("camera_id", cameraId);
        pyHeader.put("frame_id", frameId);
        pyHeader.put("product_type", productType);
        pyHeader.put("detector_id", detectorId);
        pyHeader.put("include_visuals", false);
        pyHeader.put("shm_name", frozenFrame.shmName());
        pyHeader.put("shm_offset", 0L);
        pyHeader.put("width", width);
        pyHeader.put("height", height);
        pyHeader.put("stride", stride);
        pyHeader.put("reference_shm_name", activeReference.header().get("shm_name"));
        pyHeader.put("reference_shm_offset", activeReference.header().get("shm_offset"));
        pyHeader.put("reference_width", activeReference.header().get("width"));
        pyHeader.put("reference_height", activeReference.header().get("height"));
        pyHeader.put("reference_stride", activeReference.header().get("stride"));
        Object homography = geometry == null || geometry.header() == null
                ? null
                : geometry.header().get("homographyRefToCurrent");
        String frozenName = frozenFrame.shmName() == null ? "" : frozenFrame.shmName();
        if (frozenName.contains("iml_pos")) {
            pyHeader.put(
                    "alignment_h_ref_to_cur",
                    List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            );
        } else if (homography != null) {
            pyHeader.put("alignment_h_ref_to_cur", homography);
        }
        Object roiPolygon = activeReference.header().get("interest_polygon_norm");
        if (roiPolygon instanceof List<?> points && points.size() >= 3) {
            pyHeader.put("roi_polygon_norm", points);
        }
        pyHeader.put(
                "heatmap_max_width",
                Math.max(0, YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("heatmap_preview_max_width"), 512))
        );
        return pyHeader;
    }
}
