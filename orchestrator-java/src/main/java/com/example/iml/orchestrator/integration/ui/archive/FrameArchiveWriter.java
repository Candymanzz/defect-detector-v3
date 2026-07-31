package com.example.iml.orchestrator.integration.ui.archive;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService.SaveRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;

public final class FrameArchiveWriter {

    private static final Logger LOG = LogManager.getLogger(FrameArchiveWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path archiveRoot;
    private final IntSupplier maxFramesPerCamera;

    public FrameArchiveWriter(Path archiveRoot, IntSupplier maxFramesPerCamera) {
        this.archiveRoot = archiveRoot;
        this.maxFramesPerCamera = maxFramesPerCamera;
    }

    public void savePrepared(PreparedSave prepared) {
        SaveRequest request = prepared.request();
        try {
            Path frameDir = FrameArchivePaths.frameDirectory(archiveRoot, request.cameraId(), request.frameId());
            Files.createDirectories(frameDir);
            Path storedFrame = frameDir.resolve("frame.jpg");
            Files.write(storedFrame, prepared.frameBytes());

            boolean hasHeatmap = prepared.heatmapBytes() != null && prepared.heatmapBytes().length > 0;
            if (hasHeatmap) {
                Files.write(frameDir.resolve("heatmap.u8"), prepared.heatmapBytes());
            }

            writeResultJson(frameDir.resolve("result.json"), request, hasHeatmap);
            FrameArchiveRetention.trimOldFrames(archiveRoot, request.cameraId(), maxFramesPerCamera.getAsInt());
            LOG.debug(
                    "frame archive saved camera_id={} frame_id={} heatmap={}",
                    request.cameraId(),
                    request.frameId(),
                    hasHeatmap
            );
        } catch (Exception e) {
            LOG.warn(
                    "frame archive save failed camera_id={} frame_id={}: {}",
                    request.cameraId(),
                    request.frameId(),
                    e.getMessage()
            );
        }
    }

    private void writeResultJson(Path resultPath, SaveRequest request, boolean hasHeatmap) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("camera_id", request.cameraId());
        root.put("frame_id", Long.toString(request.frameId()));
        root.put("inspection_id", Long.toString(request.inspectionId()));
        root.put("saved_at_ms", System.currentTimeMillis());
        root.put("archived", true);
        if (request.productType() != null && !request.productType().isBlank()) {
            root.put("product_type", request.productType());
        }
        if (request.detectorId() != null && !request.detectorId().isBlank()) {
            root.put("detector_id", request.detectorId());
        }
        root.put(
                "frame_http_path",
                FrameArchivePaths.frameArtifactHttpPath(request.cameraId(), request.frameId(), "frame.jpg")
        );
        if (hasHeatmap) {
            ObjectNode heatmap = root.putObject("heatmap");
            heatmap.put("width", request.heatmapWidth());
            heatmap.put("height", request.heatmapHeight());
            heatmap.put("pixel_format", "gray_u8");
            heatmap.put("channels", 1);
            heatmap.put(
                    "http_path",
                    FrameArchivePaths.frameArtifactHttpPath(request.cameraId(), request.frameId(), "heatmap.u8")
            );
        } else {
            root.putNull("heatmap");
        }
        InspectionDecision decision = request.decision();
        if (decision != null) {
            root.put("overall_pass", decision.overallPass());
            root.put("action", decision.action());
            root.put("anomaly_score", decision.anomalyScore());
            root.put("python_status", decision.pythonStatus());
            root.put("geometry_status", decision.geometryStatus());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), root);
    }

    public record PreparedSave(SaveRequest request, byte[] frameBytes, byte[] heatmapBytes) {
    }
}
