package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.config.CameraAnalysisProfiles;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.pipeline.spi.PythonInspectStage;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Вызов python-детектора для текущего кадра и результата geometry.
 */
public final class InspectPythonExecutor implements PythonInspectStage {

    private final Logger log;
    private final GeometryRuntimeConfig inspectionRuntimeConfig;

    public InspectPythonExecutor(Logger log) {
        this(log, null);
    }

    public InspectPythonExecutor(Logger log, GeometryRuntimeConfig inspectionRuntimeConfig) {
        this.log = log;
        this.inspectionRuntimeConfig = inspectionRuntimeConfig;
    }

    @Override
    public PipelineState apply(
            PipelineState state,
            int cameraId,
            String productType,
            String detectorId,
            ReferenceSnapshot activeReference,
            Map<String, Object> pythonCfg,
            List<? extends BinaryRpcSupervisor> pythonPool,
            Semaphore pythonSlots,
            AtomicInteger pythonRoundRobin
    ) {
        if (pythonPool.isEmpty()) {
            return state;
        }
        if (isPositioningHardFail(state)) {
            BinaryProtocol.Message pyFail = new BinaryProtocol.Message(
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of(
                            "status", "FAIL",
                            "ok", false,
                            "error", "positioning reject",
                            "camera_id", cameraId,
                            "product_type", productType
                    ),
                    new byte[0]
            );
            return new PipelineState(
                    state.capture(),
                    pyFail,
                    state.geom(),
                    state.captureMs(),
                    0L,
                    state.geometryMs()
            );
        }
        if (activeReference == null || activeReference.header() == null) {
            BinaryProtocol.Message pySkipped = new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of(
                            "status", "SKIPPED",
                            "error", "python inspect skipped: no reference snapshot",
                            "camera_id", cameraId,
                            "product_type", productType
                    ),
                    new byte[0]
            );
            return new PipelineState(
                    state.capture(),
                    pySkipped,
                    state.geom(),
                    state.captureMs(),
                    0L,
                    state.geometryMs()
            );
        }
        if (!hasValidCaptureFrame(state)) {
            BinaryProtocol.Message pyError = new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of(
                            "status", "ERROR",
                            "error", "python inspect skipped: invalid capture frame header",
                            "camera_id", cameraId,
                            "product_type", productType
                    ),
                    new byte[0]
            );
            return new PipelineState(
                    state.capture(),
                    pyError,
                    state.geom(),
                    state.captureMs(),
                    0L,
                    state.geometryMs()
            );
        }
        BinaryRpcSupervisor python = pythonPool.get(Math.floorMod(pythonRoundRobin.getAndIncrement(), pythonPool.size()));
        try {
            long t0 = System.nanoTime();
            Map<String, Object> pyHeader;
            boolean testAnalyze = YamlScalars.toBool(state.capture().header().get("test_analyze"), false);
            String testFramePath = String.valueOf(state.capture().header().getOrDefault("test_frame_file_path", "")).trim();
            if (testAnalyze && !testFramePath.isEmpty()) {
                int heatmapMaxWidth = Math.max(
                        0,
                        YamlScalars.toInt(pythonCfg == null ? null : pythonCfg.get("heatmap_preview_max_width"), 512)
                );
                if (heatmapMaxWidth <= 0) {
                    heatmapMaxWidth = 512;
                }
                pyHeader = BinaryInspectHeaders.pythonTestFrameInspectHeader(
                        cameraId,
                        productType,
                        detectorId,
                        state.capture(),
                        state.geom(),
                        activeReference,
                        heatmapMaxWidth
                );
                applyAnalysisProfileAndRuntimeOverrides(pyHeader, cameraId, productType, pythonCfg);
            } else {
                pyHeader = BinaryInspectHeaders.pythonInspectHeader(
                        cameraId, productType, detectorId, state.capture(), state.geom(), pythonCfg, false, activeReference);
                applyAnalysisProfileAndRuntimeOverrides(pyHeader, cameraId, productType, pythonCfg);
                Object temporaryAnalysis = state.capture().header().get("analysis_test_settings");
                if (temporaryAnalysis instanceof Map<?, ?> temporary && !temporary.isEmpty()) {
                    pyHeader.put("analysis_test_settings", temporaryAnalysis);
                }
                if (testAnalyze) {
                    pyHeader.put("test_analyze", true);
                    pyHeader.put("skip_learning_review", true);
                }
                double inspectScale = YamlScalars.toDouble(
                        pythonCfg == null ? null : pythonCfg.get("inspect_scale"),
                        1.0
                );
                boolean captureAlreadyDownscaled = state.capture() != null
                        && state.capture().header() != null
                        && YamlScalars.toDouble(state.capture().header().get("downscale_scale"), 1.0d) < 0.999d;
                if (inspectScale < 0.999d && !captureAlreadyDownscaled) {
                    PythonInspectDownscaleSupport.applyDownscaleToPythonHeader(pyHeader, cameraId, inspectScale);
                }
            }
            pythonSlots.acquire();
            try {
                BinaryProtocol.Message pyResp = python.command(pyHeader);
                if (log.isDebugEnabled()) {
                    log.debug("{} cam={} frame={} => {}", python.supervisorLabel(), cameraId, state.capture().header().get("frame_id"), pyResp.header());
                }
                return new PipelineState(
                        state.capture(),
                        pyResp,
                        state.geom(),
                        state.captureMs(),
                        YamlScalars.nanosToMs(System.nanoTime() - t0),
                        state.geometryMs()
                );
            } finally {
                pythonSlots.release();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Knobs UI и geometry-runtime живут под YAML {@code analysis_profile} камеры,
     * а {@code product_type} может быть типом эталона — не подменять одно другим.
     */
    void applyAnalysisProfileAndRuntimeOverrides(
            Map<String, Object> pyHeader,
            int cameraId,
            String productType,
            Map<String, Object> pythonCfg
    ) {
        String analysisProfile = CameraAnalysisProfiles.resolve(cameraId, productType);
        if (analysisProfile != null && !analysisProfile.isBlank()) {
            pyHeader.put("analysis_profile", analysisProfile);
        }
        if (inspectionRuntimeConfig != null) {
            inspectionRuntimeConfig.applyToPythonHeader(pyHeader, pythonCfg, analysisProfile);
        }
    }

    private static boolean hasValidCaptureFrame(PipelineState state) {
        if (state == null || state.capture() == null || state.capture().header() == null) {
            return false;
        }
        Map<String, Object> h = state.capture().header();
        String shmName = String.valueOf(h.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(h.get("width"), 0);
        int height = YamlScalars.toInt(h.get("height"), 0);
        return !shmName.isEmpty() && width > 0 && height > 0;
    }

    private static boolean isPositioningHardFail(PipelineState state) {
        return state != null
                && state.capture() != null
                && state.capture().header() != null
                && YamlScalars.toBool(state.capture().header().get(InspectPositioningExecutor.HEADER_HARD_FAIL), false);
    }
}
