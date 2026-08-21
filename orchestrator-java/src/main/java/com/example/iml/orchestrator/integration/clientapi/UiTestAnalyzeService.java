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
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        CURRENT,
        PIN
    }

    public record Request(
            int cameraId,
            Source source,
            Long frameId,
            String httpPath,
            String pinId,
            Map<String, Object> temporaryGeometry,
            Map<String, Object> temporaryAnalysis
    ) {
        public Request(int cameraId, Source source, Long frameId, String httpPath) {
            this(cameraId, source, frameId, httpPath, null, Map.of(), Map.of());
        }
        public Request(int cameraId, Source source, Long frameId, String httpPath, String pinId) {
            this(cameraId, source, frameId, httpPath, pinId, Map.of(), Map.of());
        }
    }

    public record Accepted(
            String jobId,
            int cameraId,
            long frameId,
            String pinId,
            String pinJpegSha256
    ) {
    }

    public record Pinned(
            int cameraId,
            long frameId,
            String pinId,
            String jpegSha256,
            String imageHttpPath
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
    private static final Pattern CURRENT_PATH = Pattern.compile(
            "^/api/camera/(\\d+)/current\\.jpg/?$",
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
    private final java.util.concurrent.ConcurrentHashMap<Integer, AtomicLong> jobGenerationByCamera =
            new java.util.concurrent.ConcurrentHashMap<>();
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
     * Always loads JPEG from frame-archive by cameraId+frameId — never current.jpg / live artifact.
     */
    public Pinned pin(Request request) throws AnalyzeException {
        if (request == null) {
            throw new AnalyzeException(400, "body required");
        }
        if (request.cameraId() < 0) {
            throw new AnalyzeException(400, "cameraId required");
        }
        if (request.frameId() == null) {
            throw new AnalyzeException(400, "frameId required for test pin (archive)");
        }
        if (request.source() == Source.CURRENT) {
            throw new AnalyzeException(400, "current source is not allowed for test pin; use archive");
        }
        // Ignore httpPath / artifact / current — TEST pin must be the archived frame the operator selected.
        ResolvedFrame resolved = loadArchive(request.cameraId(), request.frameId());
        if (resolved.frameId() != request.frameId()) {
            throw new AnalyzeException(
                    409,
                    "archive frame mismatch: requested=" + request.frameId() + " resolved=" + resolved.frameId()
            );
        }
        String sha = sha256Hex(resolved.jpegBytes());
        log.info(
                "ui test-pin out cam={} source=ARCHIVE frame={} bytes={} sha={} archiveHttpPath={} ignoredHttpPath={} ignoredSource={}",
                request.cameraId(),
                resolved.frameId(),
                resolved.jpegBytes().length,
                sha,
                resolved.previewHttpPath(),
                request.httpPath(),
                request.source()
        );
        try {
            TestFramePinStore.Pin pin = pinStore.pin(
                    request.cameraId(),
                    resolved.frameId(),
                    resolved.jpegBytes(),
                    sha
            );
            log.info(
                    "ui test-pin stored cam={} frame={} pinPath={} sha={}",
                    pin.cameraId(),
                    pin.frameId(),
                    pin.jpegPath(),
                    sha
            );
            return new Pinned(pin.cameraId(), pin.frameId(), pin.pinId(), pin.jpegSha256(), pin.previewHttpPath());
        } catch (IOException e) {
            throw new AnalyzeException(500, "failed to pin test frame: " + e.getMessage());
        }
    }

    public Optional<Path> pinnedJpegPath(String pinId) {
        return pinStore.get(pinId).map(TestFramePinStore.Pin::jpegPath);
    }

    public void clearPins() {
        pinStore.clearAll();
    }

    public Accepted submit(Request request) throws AnalyzeException {
        validate(request);
        ResolvedFrame resolved = resolveJpeg(request);
        if (request.source() == Source.PIN
                && request.frameId() != null
                && request.frameId().longValue() != resolved.frameId()) {
            throw new AnalyzeException(
                    409,
                    "pinned frame mismatch: requested=" + request.frameId() + " pinned=" + resolved.frameId()
            );
        }
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
        String pinSha = sha256Hex(resolved.jpegBytes());
        log.info(
                "ui test-analyze submit out jobId={} cam={} source={} frame={} bytes={} sha={} previewHttpPath={}",
                jobId,
                request.cameraId(),
                request.source(),
                frameId,
                resolved.jpegBytes().length,
                pinSha,
                resolved.previewHttpPath()
        );
        long generation = jobGenerationByCamera
                .computeIfAbsent(request.cameraId(), ignored -> new AtomicLong())
                .incrementAndGet();
        worker.execute(() -> runJob(
                jobId,
                request.cameraId(),
                frameId,
                resolved.jpegBytes(),
                ref,
                resolved.previewHttpPath(),
                request.pinId(),
                request.temporaryGeometry(),
                request.temporaryAnalysis(),
                generation
        ));
        return new Accepted(jobId, request.cameraId(), frameId, request.pinId(), pinSha);
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
            throw new AnalyzeException(400, "source required (archive|artifact|pin|current)");
        }
        if (request.source() == Source.ARCHIVE) {
            if (request.frameId() == null && (request.httpPath() == null || request.httpPath().isBlank())) {
                throw new AnalyzeException(400, "frameId or httpPath required for archive source");
            }
        } else if (request.source() == Source.ARTIFACT) {
            if (request.httpPath() == null || request.httpPath().isBlank()) {
                throw new AnalyzeException(400, "httpPath required for artifact source");
            }
        } else if (request.source() == Source.CURRENT) {
            // cameraId is enough.
        } else if (request.source() == Source.PIN) {
            if (request.pinId() == null || request.pinId().isBlank()) {
                throw new AnalyzeException(400, "pinId required for pin source");
            }
        }
    }

    private void runJob(
            String jobId,
            int cameraId,
            long frameId,
            byte[] jpegBytes,
            ReferenceSnapshot ref,
            String previewHttpPath,
            String pinId,
            Map<String, Object> temporaryGeometry,
            Map<String, Object> temporaryAnalysis,
            long generation
    ) {
        Path shmPath = null;
        try {
            String shmBase = "iml_uitest_cam" + cameraId + "_" + jobId.substring(0, Math.min(8, jobId.length()));
            JpegBgrShmWriter.WrittenFrame written = JpegBgrShmWriter.write(jpegBytes, cameraId, frameId, shmBase);
            shmPath = written.shmPath();
            Map<String, Object> captureHeader = new java.util.LinkedHashMap<>(written.captureHeader());
            captureHeader.put("test_analyze", true);
            captureHeader.put("test_analyze_job_id", jobId);
            if (pinId != null && !pinId.isBlank()) {
                captureHeader.put("test_pin_id", pinId);
            }
            captureHeader.put("test_geometry_overrides", temporaryGeometry == null ? Map.of() : temporaryGeometry);
            captureHeader.put("analysis_test_settings", temporaryAnalysis == null ? Map.of() : temporaryAnalysis);
            String pinSha = sha256Hex(jpegBytes);
            captureHeader.put("pin_jpeg_sha256", pinSha);
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
            if (isSuperseded(cameraId, generation)) {
                log.info("ui test-analyze superseded after geometry jobId={} cam={} gen={}", jobId, cameraId, generation);
                return;
            }
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
            if (isSuperseded(cameraId, generation)) {
                log.info("ui test-analyze superseded after python jobId={} cam={} gen={}", jobId, cameraId, generation);
                return;
            }
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
                    "ui test-analyze done jobId={} cam={} frame={} pin_sha={} http_path={} pass={} "
                            + "anomaly={} python={} geometry={}",
                    jobId,
                    cameraId,
                    frameId,
                    pinSha,
                    previewHttpPath,
                    decision == null ? null : decision.overallPass(),
                    decision == null ? null : decision.anomalyScore(),
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

    private boolean isSuperseded(int cameraId, long generation) {
        AtomicLong latest = jobGenerationByCamera.get(cameraId);
        return latest != null && latest.get() != generation;
    }

    private record ResolvedFrame(byte[] jpegBytes, long frameId, String previewHttpPath) {
    }

    ResolvedFrame resolveJpeg(Request request) throws AnalyzeException {
        if (request.source() == Source.PIN) {
            return loadPin(request.pinId(), request.cameraId());
        }
        if (request.source() == Source.CURRENT) {
            return loadCurrentJpeg(request.cameraId(), request.frameId());
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
            Matcher current = CURRENT_PATH.matcher(path);
            if (current.matches()) {
                int cam = Integer.parseInt(current.group(1));
                if (cam != request.cameraId()) {
                    throw new AnalyzeException(400, "httpPath cameraId mismatch");
                }
                return loadCurrentJpeg(cam, request.frameId());
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

    private ResolvedFrame loadCurrentJpeg(int cameraId, Long frameIdHint) throws AnalyzeException {
        UiHttpServer ui = uiServerSupplier.get();
        if (ui == null) {
            throw new AnalyzeException(503, "ui server unavailable");
        }
        CameraPreviewStore.Latest latest = ui.latest(cameraId).orElse(null);
        Path jpeg = latest == null ? null : latest.currentJpeg();
        if (jpeg == null || !Files.isRegularFile(jpeg)) {
            throw new AnalyzeException(404, "current.jpg not available for cameraId=" + cameraId);
        }
        try {
            byte[] bytes = Files.readAllBytes(jpeg);
            // Never label mutable current.jpg with the caller's stale frame id.
            long frameId = latest == null ? 0L : latest.frameId();
            return new ResolvedFrame(bytes, frameId, "/api/camera/" + cameraId + "/current.jpg");
        } catch (IOException e) {
            throw new AnalyzeException(500, "failed to read current.jpg: " + e.getMessage());
        }
    }

    private ResolvedFrame loadPin(String pinId, int cameraId) throws AnalyzeException {
        TestFramePinStore.Pin pin = pinStore.get(pinId)
                .orElseThrow(() -> new AnalyzeException(404, "pinned test frame not found pinId=" + pinId));
        if (pin.cameraId() != cameraId) {
            throw new AnalyzeException(409, "pin camera mismatch: requested=" + cameraId + " pinned=" + pin.cameraId());
        }
        try {
            byte[] bytes = Files.readAllBytes(pin.jpegPath());
            String pinUrl = pin.previewHttpPath();
            log.info(
                    "ui test-analyze loadPin cam={} frame={} bytes={} sha={} http_path={}",
                    cameraId,
                    pin.frameId(),
                    bytes.length,
                    sha256Hex(bytes),
                    pinUrl
            );
            return new ResolvedFrame(bytes, pin.frameId(), pinUrl);
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
            UiHttpServer.InspectionArtifactIdentity identity = ui.inspectionArtifactIdentity(bundleId);
            if (identity.cameraId() != cameraId) {
                throw new AnalyzeException(409, "artifact camera mismatch");
            }
            if (frameIdHint != null && identity.frameId() != frameIdHint.longValue()) {
                throw new AnalyzeException(
                        409,
                        "artifact frame mismatch: requested=" + frameIdHint + " artifact=" + identity.frameId()
                );
            }
            byte[] bytes = ui.readInspectionArtifact(bundleId, "frame.jpg");
            if (bytes == null || bytes.length == 0) {
                throw new AnalyzeException(404, "artifact frame not found: " + bundleId);
            }
            long frameId = identity.frameId();
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
            case "current" -> Source.CURRENT;
            case "pin" -> Source.PIN;
            default -> throw new AnalyzeException(400, "unknown source: " + raw);
        };
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";
        }
    }
}
