package com.example.iml.orchestrator.integration.ui.archive;

import com.example.iml.orchestrator.integration.ui.FrameArchiveService.ArchivedFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class FrameArchiveIndex {

    private static final Logger LOG = LogManager.getLogger(FrameArchiveIndex.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private FrameArchiveIndex() {
    }

    public static List<ArchivedFrame> listHistory(Path archiveRoot, int cameraId, int maxFrames) throws IOException {
        Path cameraDir = FrameArchivePaths.cameraDirectory(archiveRoot, cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return List.of();
        }
        List<ArchivedFrame> frames = new ArrayList<>();
        try (Stream<Path> entries = Files.list(cameraDir)) {
            for (Path frameDir : entries.filter(Files::isDirectory).toList()) {
                parseFrameDir(frameDir).ifPresent(frames::add);
            }
        }
        frames.sort(Comparator
                .comparingLong(ArchivedFrame::savedAtEpochMs)
                .reversed()
                .thenComparing(Comparator.comparingLong(ArchivedFrame::frameId).reversed()));
        return maxFrames <= 0 || frames.size() <= maxFrames ? frames : frames.subList(0, maxFrames);
    }

    public static Optional<ArchivedFrame> parseFrameDir(Path frameDir) {
        long frameId = FrameArchivePaths.parseFrameId(frameDir);
        if (frameId < 0 || !Files.isRegularFile(frameDir.resolve("frame.jpg"))) {
            return Optional.empty();
        }
        Path resultPath = frameDir.resolve("result.json");
        if (!Files.isRegularFile(resultPath)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> root = JSON.readValue(Files.readString(resultPath), new TypeReferenceMap());
            long inspectionId = parseLong(root.get("inspection_id"), frameId);
            boolean overallPass = Boolean.TRUE.equals(root.get("overall_pass"));
            String action = stringValue(root.get("action"));
            double anomalyScore = parseDouble(root.get("anomaly_score"));
            String pythonStatus = stringValue(root.get("python_status"));
            String geometryStatus = stringValue(root.get("geometry_status"));
            String productType = stringValue(root.get("product_type"));
            String detectorId = stringValue(root.get("detector_id"));
            long savedAt = parseLong(root.get("saved_at_ms"), 0L);
            if (savedAt <= 0) {
                try {
                    savedAt = Files.getLastModifiedTime(frameDir.resolve("frame.jpg")).toMillis();
                } catch (IOException ignored) {
                    savedAt = 0L;
                }
            }
            boolean hasHeatmap = Files.isRegularFile(frameDir.resolve("heatmap.u8"));
            int heatmapWidth = 0;
            int heatmapHeight = 0;
            Object heatmapRaw = root.get("heatmap");
            if (heatmapRaw instanceof Map<?, ?> heatmapMap) {
                heatmapWidth = (int) Math.max(0, parseLong(heatmapMap.get("width"), 0L));
                heatmapHeight = (int) Math.max(0, parseLong(heatmapMap.get("height"), 0L));
            }
            if (hasHeatmap && (heatmapWidth <= 0 || heatmapHeight <= 0)) {
                int[] inferredSize = inferHeatmapSize(frameDir.resolve("frame.jpg"), frameDir.resolve("heatmap.u8"));
                heatmapWidth = inferredSize[0];
                heatmapHeight = inferredSize[1];
            }
            return Optional.of(new ArchivedFrame(
                    frameId,
                    inspectionId,
                    overallPass,
                    action,
                    anomalyScore,
                    pythonStatus,
                    geometryStatus,
                    productType,
                    detectorId,
                    savedAt,
                    hasHeatmap,
                    heatmapWidth,
                    heatmapHeight
            ));
        } catch (IOException e) {
            LOG.debug("frame archive metadata read failed {}: {}", frameDir, e.getMessage());
            return Optional.empty();
        }
    }

    static int[] inferHeatmapSize(Path frameJpeg, Path heatmapU8) {
        return LegacyArchiveHeatmapSize.infer(frameJpeg, heatmapU8);
    }

    private static long parseLong(Object raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(Object raw) {
        if (raw == null) {
            return 0.0;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static final class TypeReferenceMap extends com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> {
    }
}
