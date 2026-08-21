package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.decision.InspectionDecisionPolicy;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.pipeline.spi.GeometryInspectStage;
import com.example.iml.orchestrator.integration.pipeline.spi.PythonInspectStage;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI test-analyze: JPEG by reference → temp SHM → geometry → python → WS (no capture/PLC).
 */
public final class UiTestAnalyzeService {

    public enum Source {
        ARCHIVE,
        ARTIFACT,
        PIN
    }

    public record Request(
            int cameraId,
            Source source,
            Long frameId,
            String httpPath
    ) {
    }

    public record Accepted(
            String jobId,
            int cameraId,
            long frameId
    ) {
    }

    public record Pinned(
            int cameraId,
            long frameId,
            String pinId
    ) {
    }

    public static final class AnalyzeException extends Exception {
        private final int status;

        public AnalyzeException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final Pattern ARCHIVE_PATH = Pattern.compile(
            "^/api/frame-archive/cameras/(\\d+)/frames/(\\d+)(?:/frame\\.jpg)?/?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "^/api/inspection-artifacts/([0-9a-f]{32})(?:/frame\\.jpg)?/?$",
            Pattern.CASE_INSENSITIVE
    );

    private final Logger log;
    private final PipelineReferenceRegistry referenceRegistry;
    private final Map<Integer, String> detectorByCamera;
    private final Map<String, Object> geometryCfg;
    private final Map<String, Object> pythonCfg;
    private final List<? extends BinaryRpcSupervisor> geometryPool;
    private final List<? extends BinaryRpcSupervisor> pythonPool;
    private final GeometryInspectStage geometryStage;
    private final PythonInspectStage pythonStage;
    private final InspectionDecisionPolicy decisionPolicy;
    private final AfterInspectionSidecar afterInspectionSidecar;
    private final FrameArchiveService frameArchive;
    private final TestFramePinStore pinStore;
    private final Supplier<UiHttpServer> uiServerSupplier;
    private final Supplier<Map<String, Object>> uiCfgSupplier;
    private final Supplier<BinaryRpcSupervisor> uiVisualsPythonSupplier;
    private final Supplier<ExecutorService> uiArtifactsExecutorSupplier;
    private final Semaphore geometrySlots;
    private final Semaphore pythonSlots;
    private final AtomicInteger geometryRoundRobin = new AtomicInteger();
    private final AtomicInteger pythonRoundRobin = new AtomicInteger();
    private final AtomicLong inspectionIds = new AtomicLong(System.currentTimeMillis());
    private final ExecutorService worker;

    public UiTestAnalyzeService(
            Logger log,
            PipelineReferenceRegistry referenceRegistry,
            Map<Integer, String> detectorByCamera,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg,
            List<? extends BinaryRpcSupervisor> geometryPool,
            List<? extends BinaryRpcSupervisor> pythonPool,
            GeometryInspectStage geometryStage,
            PythonInspectStage pythonStage,
            InspectionDecisionPolicy decisionPolicy,
            AfterInspectionSidecar afterInspectionSidecar,
            FrameArchiveService frameArchive,
            Supplier<UiHttpServer> uiServerSupplier,
            Supplier<Map<String, Object>> uiCfgSupplier,
            Supplier<BinaryRpcSupervisor> uiVisualsPythonSupplier,
            Supplier<ExecutorService> uiArtifactsExecutorSupplier,
            ExecutorService worker
    ) {
        this(
                log,
                referenceRegistry,
                detectorByCamera,
                geometryCfg,
                pythonCfg,
                geometryPool,
                pythonPool,
                geometryStage,
                pythonStage,
                decisionPolicy,
                afterInspectionSidecar,
                frameArchive,
                openPinStoreQuietly(),
                uiServerSupplier,
                uiCfgSupplier,
                uiVisualsPythonSupplier,
                uiArtifactsExecutorSupplier,
                worker
        );
    }

    public UiTestAnalyzeService(
            Logger log,
            PipelineReferenceRegistry referenceRegistry,
            Map<Integer, String> detectorByCamera,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg,
            List<? extends BinaryRpcSupervisor> geometryPool,
            List<? extends BinaryRpcSupervisor> pythonPool,
            GeometryInspectStage geometryStage,
            PythonInspectStage pythonStage,
            InspectionDecisionPolicy decisionPolicy,
            AfterInspectionSidecar afterInspectionSidecar,
            FrameArchiveService frameArchive,
            TestFramePinStore pinStore,
            Supplier<UiHttpServer> uiServerSupplier,
            Supplier<Map<String, Object>> uiCfgSupplier,
            Supplier<BinaryRpcSupervisor> uiVisualsPythonSupplier,
            Supplier<ExecutorService> uiArtifactsExecutorSupplier,
            ExecutorService worker
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.referenceRegistry = Objects.requireNonNull(referenceRegistry, "referenceRegistry");
        this.detectorByCamera = detectorByCamera == null ? Map.of() : Map.copyOf(detectorByCamera);
        this.geometryCfg = geometryCfg;
        this.pythonCfg = pythonCfg;
        this.geometryPool = geometryPool == null ? List.of() : List.copyOf(geometryPool);
        this.pythonPool = pythonPool == null ? List.of() : List.copyOf(pythonPool);
        this.geometryStage = Objects.requireNonNull(geometryStage, "geometryStage");
        this.pythonStage = Objects.requireNonNull(pythonStage, "pythonStage");
        this.decisionPolicy = Objects.requireNonNull(decisionPolicy, "decisionPolicy");
        this.afterInspectionSidecar = Objects.requireNonNull(afterInspectionSidecar, "afterInspectionSidecar");
        this.frameArchive = frameArchive;
        this.pinStore = Objects.requireNonNull(pinStore, "pinStore");
        this.uiServerSupplier = uiServerSupplier == null ? () -> null : uiServerSupplier;
        this.uiCfgSupplier = uiCfgSupplier == null ? () -> null : uiCfgSupplier;
        this.uiVisualsPythonSupplier = uiVisualsPythonSupplier == null ? () -> null : uiVisualsPythonSupplier;
        this.uiArtifactsExecutorSupplier = uiArtifactsExecutorSupplier == null ? () -> null : uiArtifactsExecutorSupplier;
        this.geometrySlots = new Semaphore(Math.max(1, this.geometryPool.size()));
        this.pythonSlots = new Semaphore(Math.max(1, this.pythonPool.size()));
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    private static TestFramePinStore openPinStoreQuietly() {
        try {
            return TestFramePinStore.openDefault();
        } catch (IOException e) {
            throw new IllegalStateException("test frame pin store unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Copy the operator-selected frame into a durable pin for the TEST session.
     * Source must be archive or artifact (not pin).
     */
    public Pinned pin(Request request) throws AnalyzeException {
        if (request == null) {
            throw new AnalyzeException(400, "body required");
        }
        if (request.cameraId() < 0) {
            throw new AnalyzeException(400, "cameraId required");
        }
        if (request.source() == null || request.source() == Source.PIN) {
            throw new AnalyzeException(400, "source required (archive|artifact)");
        }
        Request resolveRequest = request;
        if (request.source() == Source.ARCHIVE) {
            if (request.frameId() == null && (request.httpPath() == null || request.httpPath().isBlank())) {
                throw new AnalyzeException(400, "frameId or httpPath required for archive source");
            }
        } else if (request.source() == Source.ARTIFACT) {
            if (request.httpPath() == null || request.httpPath().isBlank()) {
                throw new AnalyzeException(400, "httpPath required for artifact source");
            }
        }
        ResolvedFrame resolved;
        try {
            resolved = resolveJpeg(resolveRequest);
        } catch (AnalyzeException primary) {
            // Artifact bundles expire quickly; fall back to archive by frameId when possible.
            if (request.source() == Source.ARTIFACT
                    && request.frameId() != null
                    && primary.status() == 404) {
                resolved = loadArchive(request.cameraId(), request.frameId());
            } else {
                throw primary;
            }
        }
        try {
            TestFramePinStore.Pin pin = pinStore.pin(
                    request.cameraId(),
                    resolved.frameId(),
                    resolved.jpegBytes(),
                    // Durable URL: archive/artifact paths can roll/expire while TEST settings stay open.
                    "/api/client/inspection/test-pin/cameras/" + request.cameraId() + "/frame.jpg"
            );
            return new Pinned(pin.cameraId(), pin.frameId(), "cam-" + pin.cameraId());
        } catch (IOException e) {
            throw new AnalyzeException(500, "failed to pin test frame: " + e.getMessage());
        }
    }

    public Optional<Path> pinnedJpegPath(int cameraId) {
        return pinStore.get(cameraId).map(TestFramePinStore.Pin::jpegPath);
    }

    public void clearPins() {
        pinStore.clearAll();
    }

    public Accepted submit(Request request) throws AnalyzeException {
        validate(request);
        ResolvedFrame resolved = resolveJpeg(request);
        ReferenceSnapshot ref = referenceRegistry.get(request.cameraId());
        if (ref == null || !ref.isUsable()) {
            throw new AnalyzeException(409, "no usable reference for camera " + request.cameraId());
        }
        if (geometryPool.isEmpty()) {
            throw new AnalyzeException(503, "geometry pool is empty");
        }
        if (pythonPool.isEmpty()) {
            throw new AnalyzeException(503, "python pool is empty");
        }
        String jobId = UUID.randomUUID().toString().replace("-", "");
        long frameId = resolved.frameId();
        worker.execute(() -> runJob(jobId, request.cameraId(), frameId, resolved.jpegBytes(), ref, resolved.previewHttpPath()));
        return new Accepted(jobId, request.cameraId(), frameId);
    }

    private void validate(Request request) throws AnalyzeException {
        if (request == null) {
            throw new AnalyzeException(400, "body required");
        }
        if (request.cameraId() < 0) {
            throw new AnalyzeException(400, "cameraId required");
        }
        if (!detectorByCamera.containsKey(request.cameraId())
                && (referenceRegistry.get(request.cameraId()) == null)) {
            // Still allow if reference exists for unknown-but-present camera; else 404.
            if (request.source() == Source.ARCHIVE && request.frameId() == null
                    && (request.httpPath() == null || request.httpPath().isBlank())) {
                throw new AnalyzeException(404, "unknown cameraId=" + request.cameraId());
            }
        }
        if (request.source() == null) {
            throw new AnalyzeException(400, "source required (archive|artifact|pin)");
        }
        if (request.source() == Source.ARCHIVE) {
            if (request.frameId() == null && (request.httpPath() == null || request.httpPath().isBlank())) {
                throw new AnalyzeException(400, "frameId or httpPath required for archive source");
            }
        } else if (request.source() == Source.ARTIFACT) {
            if (request.httpPath() == null || request.httpPath().isBlank()) {
                throw new AnalyzeException(400, "httpPath required for artifact source");
            }
        } else if (request.source() == Source.PIN) {
            // cameraId is enough; frameId is optional and used only for logging/UI.
        }
    }

    private void runJob(
            String jobId,
            int cameraId,
            long frameId,
            byte[] jpegBytes,
            ReferenceSnapshot ref,
            String previewHttpPath
    ) {
        Path shmPath = null;
        try {
            String shmBase = "iml_uitest_cam" + cameraId + "_" + jobId.substring(0, Math.min(8, jobId.length()));
            JpegBgrShmWriter.WrittenFrame written = JpegBgrShmWriter.write(jpegBytes, cameraId, frameId, shmBase);
            shmPath = written.shmPath();
            Map<String, Object> captureHeader = new java.util.LinkedHashMap<>(written.captureHeader());
            captureHeader.put("test_analyze", true);
            if (previewHttpPath != null && !previewHttpPath.isBlank()) {
                captureHeader.put("http_path", previewHttpPath);
            }
            BinaryProtocol.Message capture = new BinaryProtocol.Message(
                    BinaryProtocol.MSG_RESPONSE,
                    Map.copyOf(captureHeader),
                    new byte[0]
            );
            PipelineState state = new PipelineState(capture, null, null, 0L, 0L, 0L);
            String productType = ref.productType() == null ? "" : ref.productType();
            String detectorId = detectorByCamera.getOrDefault(cameraId, "v1");

            state = geometryStage.apply(
                    state,
                    cameraId,
                    productType,
                    ref,
                    geometryCfg,
                    pythonCfg,
                    geometryPool,
                    geometrySlots,
                    geometryRoundRobin
            );
            state = pythonStage.apply(
                    state,
                    cameraId,
                    productType,
                    detectorId,
                    ref,
                    pythonCfg,
                    pythonPool,
                    pythonSlots,
                    pythonRoundRobin
            );
            InspectionDecision decision = decisionPolicy.decide(cameraId, state.capture(), state.py(), state.geom());
            long inspectionId = inspectionIds.incrementAndGet();
            afterInspectionSidecar.scheduleAfterInspection(
                    uiServerSupplier.get(),
                    uiCfgSupplier.get(),
                    uiVisualsPythonSupplier.get(),
                    uiArtifactsExecutorSupplier.get(),
                    cameraId,
                    productType,
                    detectorId,
                    inspectionId,
                    ref,
                    decision,
                    state.capture(),
                    state.py(),
                    state.geom()
            );
            log.info(
                    "ui test-analyze done jobId={} cam={} frame={} pass={} python={} geometry={}",
                    jobId,
                    cameraId,
                    frameId,
                    decision == null ? null : decision.overallPass(),
                    decision == null ? null : decision.pythonStatus(),
                    decision == null ? null : decision.geometryStatus()
            );
        } catch (Exception e) {
            log.warn("ui test-analyze failed jobId={} cam={} frame={}: {}", jobId, cameraId, frameId, e.getMessage());
            JpegBgrShmWriter.deleteQuietly(shmPath);
            shmPath = null;
        } finally {
            // Async UI sidecar still reads SHM for JPEG/heatmap; delay cleanup.
            Path toDelete = shmPath;
            if (toDelete != null) {
                // Never sleep on the single test-analyze executor: doing so queued every
                // repeated check behind this cleanup for 90 seconds.
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> JpegBgrShmWriter.deleteQuietly(toDelete),
                        java.util.concurrent.CompletableFuture.delayedExecutor(
                                90L,
                                java.util.concurrent.TimeUnit.SECONDS
                        )
                );
            }
        }
    }

    private record ResolvedFrame(byte[] jpegBytes, long frameId, String previewHttpPath) {
    }

    ResolvedFrame resolveJpeg(Request request) throws AnalyzeException {
        if (request.source() == Source.PIN) {
            return loadPin(request.cameraId());
        }
        String path = request.httpPath() == null ? "" : request.httpPath().trim();
        if (!path.isEmpty()) {
            Matcher archive = ARCHIVE_PATH.matcher(path);
            if (archive.matches()) {
                int cam = Integer.parseInt(archive.group(1));
                long fid = Long.parseLong(archive.group(2));
                if (cam != request.cameraId()) {
                    throw new AnalyzeException(400, "httpPath cameraId mismatch");
                }
                return loadArchive(cam, fid);
            }
            Matcher artifact = ARTIFACT_PATH.matcher(path);
            if (artifact.matches()) {
                return loadArtifact(artifact.group(1), request.cameraId(), request.frameId());
            }
            if (request.source() == Source.ARTIFACT) {
                throw new AnalyzeException(400, "unsupported artifact httpPath: " + path);
            }
        }
        if (request.source() == Source.ARCHIVE) {
            if (request.frameId() == null) {
                throw new AnalyzeException(400, "frameId required");
            }
            return loadArchive(request.cameraId(), request.frameId());
        }
        throw new AnalyzeException(400, "cannot resolve frame reference");
    }

    private ResolvedFrame loadPin(int cameraId) throws AnalyzeException {
        TestFramePinStore.Pin pin = pinStore.get(cameraId)
                .orElseThrow(() -> new AnalyzeException(404, "no pinned test frame for cameraId=" + cameraId));
        try {
            byte[] bytes = Files.readAllBytes(pin.jpegPath());
            return new ResolvedFrame(bytes, pin.frameId(), pin.previewHttpPath());
        } catch (IOException e) {
            throw new AnalyzeException(500, "failed to read pinned test frame: " + e.getMessage());
        }
    }

    private ResolvedFrame loadArchive(int cameraId, long frameId) throws AnalyzeException {
        if (frameArchive == null || !frameArchive.enabled()) {
            throw new AnalyzeException(503, "frame archive unavailable");
        }
        Path jpeg = frameArchive.resolveArtifact(cameraId, frameId, "frame.jpg")
                .orElseThrow(() -> new AnalyzeException(404, "frame not found in archive cameraId="
                        + cameraId + " frameId=" + frameId));
        try {
            byte[] bytes = Files.readAllBytes(jpeg);
            String httpPath = frameArchive.frameArtifactHttpPath(cameraId, frameId, "frame.jpg");
            return new ResolvedFrame(bytes, frameId, httpPath);
        } catch (IOException e) {
            throw new AnalyzeException(500, "failed to read archive frame: " + e.getMessage());
        }
    }

    private ResolvedFrame loadArtifact(String bundleId, int cameraId, Long frameIdHint) throws AnalyzeException {
        UiHttpServer ui = uiServerSupplier.get();
        if (ui == null) {
            throw new AnalyzeException(503, "ui server unavailable");
        }
        try {
            byte[] bytes = ui.readInspectionArtifact(bundleId, "frame.jpg");
            if (bytes == null || bytes.length == 0) {
                throw new AnalyzeException(404, "artifact frame not found: " + bundleId);
            }
            long frameId = frameIdHint != null ? frameIdHint : Math.abs(bundleId.hashCode());
            String httpPath = "/api/inspection-artifacts/" + bundleId + "/frame.jpg";
            return new ResolvedFrame(bytes, frameId, httpPath);
        } catch (AnalyzeException e) {
            throw e;
        } catch (IOException e) {
            throw new AnalyzeException(404, "artifact frame not found: " + e.getMessage());
        }
    }

    public static Source parseSource(String raw) throws AnalyzeException {
        if (raw == null || raw.isBlank()) {
            throw new AnalyzeException(400, "source required");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "archive" -> Source.ARCHIVE;
            case "artifact" -> Source.ARTIFACT;
            case "pin" -> Source.PIN;
            default -> throw new AnalyzeException(400, "unknown source: " + raw);
        };
    }
}
