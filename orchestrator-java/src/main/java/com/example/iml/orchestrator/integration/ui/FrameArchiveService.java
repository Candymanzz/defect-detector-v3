package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FrameArchiveService implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(FrameArchiveService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FRAME_DIR = Pattern.compile("^f_(\\d+)$");

    public record SaveRequest(
            int cameraId,
            long frameId,
            long inspectionId,
            int phaseId,
            int groupId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            Path frameJpeg,
            Path heatmapU8,
            int heatmapWidth,
            int heatmapHeight
    ) {
        public SaveRequest(
                int cameraId,
                long frameId,
                long inspectionId,
                String productType,
                String detectorId,
                InspectionDecision decision,
                Path frameJpeg,
                Path heatmapU8,
                int heatmapWidth,
                int heatmapHeight
        ) {
            this(cameraId, frameId, inspectionId, 0, -1, productType, detectorId, decision, frameJpeg, heatmapU8,
                    heatmapWidth, heatmapHeight);
        }
    }

    public record ArchivedFrame(
            long frameId,
            long inspectionId,
            int phaseId,
            int groupId,
            boolean overallPass,
            String action,
            double anomalyScore,
            String pythonStatus,
            String geometryStatus,
            String productType,
            String detectorId,
            long savedAtEpochMs,
            boolean hasHeatmap,
            int heatmapWidth,
            int heatmapHeight
    ) {
    }

    private final FrameArchiveConfig config;
    private final FrameArchiveSettingsStore settingsStore;
    private final ThreadPoolExecutor executor;

    private FrameArchiveService(FrameArchiveConfig config, FrameArchiveSettingsStore settingsStore, ThreadPoolExecutor executor) {
        this.config = config;
        this.settingsStore = settingsStore;
        this.executor = executor;
    }

    public static FrameArchiveService open(FrameArchiveConfig config) throws IOException {
        if (config == null || !config.enabled()) {
            return null;
        }
        Files.createDirectories(config.directory());
        FrameArchiveSettingsStore settingsStore = FrameArchiveSettingsStore.open(
                config.directory(),
                config.defaultMaxFramesPerCamera(),
                config.maxAllowedFramesPerCamera()
        );
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                2,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "frame-archive");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        LOG.info(
                "frame archive enabled directory={} max_frames_per_camera={}",
                config.directory(),
                settingsStore.maxFramesPerCamera()
        );
        return new FrameArchiveService(config, settingsStore, executor);
    }

    public boolean enabled() {
        return config != null && config.enabled();
    }

    public Path directory() {
        return config.directory();
    }

    public int maxFramesPerCamera() {
        return settingsStore.maxFramesPerCamera();
    }

    public int maxAllowedFramesPerCamera() {
        return config.maxAllowedFramesPerCamera();
    }

    public void setMaxFramesPerCamera(int value) throws IOException {
        settingsStore.setMaxFramesPerCamera(value);
        int applied = settingsStore.maxFramesPerCamera();
        trimAllCameras();
        LOG.info("frame archive max_frames_per_camera set to {}", applied);
    }

    private void trimAllCameras() throws IOException {
        if (!Files.isDirectory(config.directory())) {
            return;
        }
        try (Stream<Path> entries = Files.list(config.directory())) {
            for (Path cameraDir : entries.filter(Files::isDirectory).toList()) {
                String name = cameraDir.getFileName().toString();
                if (!name.startsWith("camera_")) {
                    continue;
                }
                try {
                    int cameraId = Integer.parseInt(name.substring("camera_".length()));
                    trimOldFrames(cameraId);
                } catch (NumberFormatException ignored) {
                    // skip non-camera directories
                }
            }
        }
    }

    public void scheduleSave(SaveRequest request) {
        if (!enabled() || request == null || request.frameJpeg() == null || !Files.isRegularFile(request.frameJpeg())) {
            return;
        }
        if (maxFramesPerCamera() <= 0) {
            return;
        }
        // Snapshot bytes while source paths are still valid (UI finally / next inspection may delete them).
        final byte[] frameBytes;
        final byte[] heatmapBytes;
        try {
            frameBytes = Files.readAllBytes(request.frameJpeg());
            heatmapBytes = request.heatmapU8() != null && Files.isRegularFile(request.heatmapU8())
                    ? Files.readAllBytes(request.heatmapU8())
                    : null;
        } catch (IOException e) {
            LOG.warn(
                    "frame archive snapshot failed camera_id={} frame_id={}: {}",
                    request.cameraId(),
                    request.frameId(),
                    e.getMessage()
            );
            return;
        }
        PreparedSave prepared = new PreparedSave(request, frameBytes, heatmapBytes);
        try {
            executor.execute(() -> savePrepared(prepared));
        } catch (RejectedExecutionException e) {
            // Do not drop frames when the queue is full — write on the caller thread.
            savePrepared(prepared);
        }
    }

    /**
     * Snapshot + write immediately on the caller thread. Safe to call before ephemeral UI files are deleted.
     * Does not block the inspection pipeline (runs on the UI publish worker).
     */
    public boolean saveImmediately(SaveRequest request) {
        if (!enabled() || request == null || request.frameJpeg() == null || !Files.isRegularFile(request.frameJpeg())) {
            return false;
        }
        if (maxFramesPerCamera() <= 0) {
            return false;
        }
        try {
            byte[] frameBytes = Files.readAllBytes(request.frameJpeg());
            byte[] heatmapBytes = request.heatmapU8() != null && Files.isRegularFile(request.heatmapU8())
                    ? Files.readAllBytes(request.heatmapU8())
                    : null;
            savePrepared(new PreparedSave(request, frameBytes, heatmapBytes));
            return Files.isRegularFile(frameDirectory(request.cameraId(), request.frameId()).resolve("frame.jpg"));
        } catch (Exception e) {
            LOG.warn(
                    "frame archive immediate save failed camera_id={} frame_id={}: {}",
                    request.cameraId(),
                    request.frameId(),
                    e.getMessage()
            );
            return false;
        }
    }

    public List<ArchivedFrame> listHistory(int cameraId) throws IOException {
        if (!enabled()) {
            return List.of();
        }
        Path cameraDir = cameraDirectory(cameraId);
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
        int limit = maxFramesPerCamera();
        return limit <= 0 || frames.size() <= limit ? frames : frames.subList(0, limit);
    }

    public boolean deleteFrame(int cameraId, long frameId) {
        if (!enabled()) {
            return false;
        }
        Path frameDir = frameDirectory(cameraId, frameId);
        if (!Files.isDirectory(frameDir)) {
            return false;
        }
        deleteFrameDirectory(frameDir);
        return !Files.exists(frameDir);
    }

    public int deleteFrames(int cameraId, Iterable<Long> frameIds) {
        int deleted = 0;
        if (frameIds == null) {
            return 0;
        }
        for (Long frameId : frameIds) {
            if (frameId != null && deleteFrame(cameraId, frameId)) {
                deleted++;
            }
        }
        return deleted;
    }

    public int clearCamera(int cameraId) throws IOException {
        if (!enabled()) {
            return 0;
        }
        Path cameraDir = cameraDirectory(cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> entries = Files.list(cameraDir)) {
            for (Path frameDir : entries.filter(Files::isDirectory).toList()) {
                if (parseFrameId(frameDir) >= 0) {
                    deleteFrameDirectory(frameDir);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    public int clearAll() throws IOException {
        if (!enabled() || !Files.isDirectory(config.directory())) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> entries = Files.list(config.directory())) {
            for (Path cameraDir : entries.filter(Files::isDirectory).toList()) {
                String name = cameraDir.getFileName().toString();
                if (!name.startsWith("camera_")) {
                    continue;
                }
                try {
                    deleted += clearCamera(Integer.parseInt(name.substring("camera_".length())));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return deleted;
    }

    public Optional<Path> resolveArtifact(int cameraId, long frameId, String artifactName) {
        if (!enabled() || artifactName == null || artifactName.isBlank()) {
            return Optional.empty();
        }
        Path artifact = frameDirectory(cameraId, frameId).resolve(sanitizeArtifactName(artifactName));
        return Files.isRegularFile(artifact) ? Optional.of(artifact) : Optional.empty();
    }

    public String frameArtifactHttpPath(int cameraId, long frameId, String artifactName) {
        return "/api/frame-archive/cameras/" + cameraId + "/frames/" + frameId + "/" + sanitizeArtifactName(artifactName);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private void saveNow(SaveRequest request) {
        try {
            byte[] frameBytes = Files.readAllBytes(request.frameJpeg());
            byte[] heatmapBytes = request.heatmapU8() != null && Files.isRegularFile(request.heatmapU8())
                    ? Files.readAllBytes(request.heatmapU8())
                    : null;
            savePrepared(new PreparedSave(request, frameBytes, heatmapBytes));
        } catch (Exception e) {
            LOG.warn(
                    "frame archive save failed camera_id={} frame_id={}: {}",
                    request.cameraId(),
                    request.frameId(),
                    e.getMessage()
            );
        }
    }

    private void savePrepared(PreparedSave prepared) {
        SaveRequest request = prepared.request();
        try {
            Path frameDir = frameDirectory(request.cameraId(), request.frameId());
            Files.createDirectories(frameDir);
            Path storedFrame = frameDir.resolve("frame.jpg");
            Files.write(storedFrame, prepared.frameBytes());

            boolean hasHeatmap = prepared.heatmapBytes() != null && prepared.heatmapBytes().length > 0;
            if (hasHeatmap) {
                Files.write(frameDir.resolve("heatmap.u8"), prepared.heatmapBytes());
            }

            writeResultJson(frameDir.resolve("result.json"), request, hasHeatmap);
            trimOldFrames(request.cameraId());
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

    private record PreparedSave(SaveRequest request, byte[] frameBytes, byte[] heatmapBytes) {
    }

    private void writeResultJson(Path resultPath, SaveRequest request, boolean hasHeatmap) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("camera_id", request.cameraId());
        root.put("frame_id", Long.toString(request.frameId()));
        root.put("inspection_id", Long.toString(request.inspectionId()));
        root.put("phase_id", request.phaseId());
        root.put("group_id", request.groupId());
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
                frameArtifactHttpPath(request.cameraId(), request.frameId(), "frame.jpg")
        );
        if (hasHeatmap) {
            ObjectNode heatmap = root.putObject("heatmap");
            heatmap.put("width", request.heatmapWidth());
            heatmap.put("height", request.heatmapHeight());
            heatmap.put("pixel_format", "gray_u8");
            heatmap.put("channels", 1);
            heatmap.put(
                    "http_path",
                    frameArtifactHttpPath(request.cameraId(), request.frameId(), "heatmap.u8")
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

    private void trimOldFrames(int cameraId) throws IOException {
        int maxFrames = maxFramesPerCamera();
        if (maxFrames <= 0) {
            deleteCameraFrames(cameraId);
            return;
        }
        Path cameraDir = cameraDirectory(cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return;
        }
        // Newest by saved_at first; drop oldest when over the configured limit (ring buffer).
        List<ArchivedFrame> frames = new ArrayList<>();
        try (Stream<Path> entries = Files.list(cameraDir)) {
            for (Path frameDir : entries.filter(Files::isDirectory).toList()) {
                parseFrameDir(frameDir).ifPresent(frames::add);
            }
        }
        if (frames.size() <= maxFrames) {
            return;
        }
        frames.sort(Comparator
                .comparingLong(ArchivedFrame::savedAtEpochMs)
                .reversed()
                .thenComparing(Comparator.comparingLong(ArchivedFrame::frameId).reversed()));
        for (int index = maxFrames; index < frames.size(); index++) {
            deleteFrameDirectory(frameDirectory(cameraId, frames.get(index).frameId()));
        }
    }

    private void deleteCameraFrames(int cameraId) throws IOException {
        Path cameraDir = cameraDirectory(cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(cameraDir)) {
            entries.filter(Files::isDirectory).forEach(FrameArchiveService::deleteFrameDirectory);
        }
    }

    private Optional<ArchivedFrame> parseFrameDir(Path frameDir) {
        long frameId = parseFrameId(frameDir);
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
            int phaseId = (int) parseLong(root.get("phase_id"), 0L);
            int groupId = (int) parseLong(root.get("group_id"), -1L);
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
                    phaseId,
                    groupId,
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

    /**
     * Compatibility for archives written before heatmap dimensions were persisted reliably.
     * gray_u8 contains exactly one byte per pixel; choose the factor pair closest to the JPEG aspect ratio.
     */
    private static int[] inferHeatmapSize(Path frameJpeg, Path heatmapU8) {
        try {
            long pixelCount = Files.size(heatmapU8);
            if (pixelCount <= 0 || pixelCount > (long) Integer.MAX_VALUE * Integer.MAX_VALUE) {
                return new int[]{0, 0};
            }
            BufferedImage frame = ImageIO.read(frameJpeg.toFile());
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                return new int[]{0, 0};
            }
            if (pixelCount == (long) frame.getWidth() * frame.getHeight()) {
                return new int[]{frame.getWidth(), frame.getHeight()};
            }

            double targetAspect = (double) frame.getWidth() / frame.getHeight();
            int bestWidth = 0;
            int bestHeight = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            for (long divisor = 1; divisor * divisor <= pixelCount; divisor++) {
                if (pixelCount % divisor != 0) {
                    continue;
                }
                long quotient = pixelCount / divisor;
                if (quotient > Integer.MAX_VALUE) {
                    continue;
                }
                int[][] candidates = {
                        {(int) quotient, (int) divisor},
                        {(int) divisor, (int) quotient}
                };
                for (int[] candidate : candidates) {
                    double aspect = (double) candidate[0] / candidate[1];
                    double score = Math.abs(Math.log(aspect / targetAspect));
                    if (score < bestScore) {
                        bestScore = score;
                        bestWidth = candidate[0];
                        bestHeight = candidate[1];
                    }
                }
            }
            if (bestWidth > 0) {
                LOG.info(
                        "inferred legacy archive heatmap size {}x{} from {} bytes frame={}",
                        bestWidth,
                        bestHeight,
                        pixelCount,
                        frameJpeg
                );
            }
            return new int[]{bestWidth, bestHeight};
        } catch (Exception e) {
            LOG.debug("legacy archive heatmap size inference failed {}: {}", heatmapU8, e.getMessage());
            return new int[]{0, 0};
        }
    }

    private Path cameraDirectory(int cameraId) {
        return config.directory().resolve("camera_" + cameraId);
    }

    private Path frameDirectory(int cameraId, long frameId) {
        return cameraDirectory(cameraId).resolve(String.format(Locale.ROOT, "f_%07d", frameId));
    }

    private static long parseFrameId(Path frameDir) {
        Matcher matcher = FRAME_DIR.matcher(frameDir.getFileName().toString());
        if (!matcher.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String sanitizeArtifactName(String artifactName) {
        String normalized = artifactName.trim();
        return switch (normalized) {
            case "frame.jpg", "heatmap.u8", "result.json" -> normalized;
            default -> throw new IllegalArgumentException("unsupported artifact: " + artifactName);
        };
    }

    private static void deleteFrameDirectory(Path frameDir) {
        try {
            Files.deleteIfExists(frameDir.resolve("frame.jpg"));
            Files.deleteIfExists(frameDir.resolve("heatmap.u8"));
            Files.deleteIfExists(frameDir.resolve("result.json"));
            Files.deleteIfExists(frameDir);
        } catch (IOException ignored) {
        }
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
