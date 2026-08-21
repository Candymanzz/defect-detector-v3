package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.config.CameraAnalysisProfiles;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.GeometryInspectStage;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Вызов geometry-сервиса для текущего кадра (семафор пула, round-robin).
 */
public final class InspectGeometryExecutor implements GeometryInspectStage {

    private final Logger log;
    private final GeometrySnapshotCache geometrySnapshotCache;
    private final GeometryRuntimeConfig geometryRuntimeConfig;
    private final InspectPositioningExecutor positioningExecutor;
    private final Set<Integer> geometryDisabledCameras;

    public InspectGeometryExecutor(Logger log) {
        this(log, null, null, null, Set.of());
    }

    public InspectGeometryExecutor(Logger log, GeometrySnapshotCache geometrySnapshotCache) {
        this(log, geometrySnapshotCache, null, null, Set.of());
    }

    public InspectGeometryExecutor(
            Logger log,
            GeometrySnapshotCache geometrySnapshotCache,
            GeometryRuntimeConfig geometryRuntimeConfig
    ) {
        this(log, geometrySnapshotCache, geometryRuntimeConfig, null, Set.of());
    }

    public InspectGeometryExecutor(
            Logger log,
            GeometrySnapshotCache geometrySnapshotCache,
            GeometryRuntimeConfig geometryRuntimeConfig,
            InspectPositioningExecutor positioningExecutor
    ) {
        this(log, geometrySnapshotCache, geometryRuntimeConfig, positioningExecutor, Set.of());
    }

    public InspectGeometryExecutor(
            Logger log,
            GeometrySnapshotCache geometrySnapshotCache,
            GeometryRuntimeConfig geometryRuntimeConfig,
            InspectPositioningExecutor positioningExecutor,
            Set<Integer> geometryDisabledCameras
    ) {
        this.log = log;
        this.geometrySnapshotCache = geometrySnapshotCache;
        this.geometryRuntimeConfig = geometryRuntimeConfig;
        this.positioningExecutor = positioningExecutor;
        this.geometryDisabledCameras = geometryDisabledCameras == null
                ? Set.of()
                : Set.copyOf(geometryDisabledCameras);
    }

    @Override
    public PipelineState apply(
            PipelineState state,
            int cameraId,
            String productType,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg,
            List<? extends BinaryRpcSupervisor> geometryPool,
            Semaphore geometrySlots,
            AtomicInteger geometryRoundRobin
    ) {
        if (geometryDisabledCameras.contains(cameraId)) {
            log.info("integration cam={}: geometry skipped (geometry_enabled=false)", cameraId);
            return withSkippedGeometryPass(state, cameraId, "geometry disabled for camera");
        }
        if (activeReference == null || activeReference.header() == null) {
            return withSkippedGeometryPass(state, cameraId, "geometry skipped: no reference snapshot");
        }
        if (BinaryInspectHeaders.isClientReferenceWithoutJointRoi(activeReference)) {
            log.info("integration cam={}: geometry skipped (client reference without joint ROI)", cameraId);
            return withSkippedGeometryPass(state, cameraId, "geometry skipped: no joint ROI from client");
        }
        if (positioningExecutor != null) {
            state = positioningExecutor.apply(state, cameraId, productType, activeReference, geometryCfg, pythonCfg);
        }
        if (isPositioningHardFail(state)) {
            BinaryProtocol.Message geomFail = positioningRejectMessage(state, cameraId);
            return new PipelineState(
                    state.capture(),
                    state.py(),
                    geomFail,
                    state.captureMs(),
                    state.pythonMs(),
                    0L
            );
        }
        if (geometryPool.isEmpty()) {
            return state;
        }
        if (!hasValidCaptureFrame(state)) {
            BinaryProtocol.Message geomError = new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of(
                            "status", "ERROR",
                            "error", "geometry skipped: invalid capture frame header",
                            "camera_id", cameraId
                    ),
                    new byte[0]
            );
            return new PipelineState(
                    state.capture(),
                    state.py(),
                    geomError,
                    state.captureMs(),
                    state.pythonMs(),
                    0L
            );
        }
        BinaryRpcSupervisor geometry = geometryPool.get(Math.floorMod(geometryRoundRobin.getAndIncrement(), geometryPool.size()));
        try {
            long t0 = System.nanoTime();
            Map<String, Object> gHeader = BinaryInspectHeaders.geometryInspectHeader(
                    cameraId, state.capture(), activeReference, geometryCfg, pythonCfg);
            if (geometryRuntimeConfig != null) {
                // UI пишет geometry-runtime под analysis_profile камеры, не под product_type эталона.
                geometryRuntimeConfig.applyToGeometryHeader(
                        gHeader,
                        CameraAnalysisProfiles.resolve(cameraId, productType)
                );
            }
            BinaryInspectHeaders.applyMainRoiFromPolygon(gHeader, state.capture(), activeReference);
            BinaryInspectHeaders.syncWrinklesRoiFromMainRoi(gHeader);
            if (YamlScalars.toBool(state.capture().header().get("test_analyze"), false)) {
                log.info(
                        "ui analysis-test geometry payload jobId={} cam={} frame={} analysisProfile={} worker={} values={}",
                        state.capture().header().get("test_analyze_job_id"),
                        cameraId,
                        state.capture().header().get("frame_id"),
                        CameraAnalysisProfiles.resolve(cameraId, productType),
                        geometry.supervisorLabel(),
                        gHeader
                );
            }
            geometrySlots.acquire();
            try {
                BinaryProtocol.Message geomResp = geometry.command(gHeader);
                if (log.isDebugEnabled()) {
                    log.debug("{} cam={} frame={} => {}", geometry.supervisorLabel(), cameraId, state.capture().header().get("frame_id"), geomResp.header());
                }
                if (geometrySnapshotCache != null && geomResp.type() == BinaryProtocol.MSG_RESPONSE) {
                    long frameId = YamlScalars.toLong(state.capture().header().get("frame_id"), -1L);
                    geometrySnapshotCache.record(cameraId, frameId, geomResp.header());
                }
                return new PipelineState(
                        state.capture(),
                        state.py(),
                        geomResp,
                        state.captureMs(),
                        state.pythonMs(),
                        YamlScalars.nanosToMs(System.nanoTime() - t0)
                );
            } finally {
                geometrySlots.release();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static PipelineState withSkippedGeometryPass(PipelineState state, int cameraId, String reason) {
        Map<String, Object> header = new java.util.LinkedHashMap<>();
        header.put("status", "SKIPPED");
        header.put("overallPass", true);
        header.put("alignmentPass", true);
        header.put("jointPass", true);
        header.put("wrinklesPass", true);
        header.put("jointCamera", false);
        header.put("camera_id", cameraId);
        header.put("error", reason);
        BinaryProtocol.Message geomSkipped = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                header,
                new byte[0]
        );
        return new PipelineState(
                state.capture(),
                state.py(),
                geomSkipped,
                state.captureMs(),
                state.pythonMs(),
                0L
        );
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

    private static BinaryProtocol.Message positioningRejectMessage(PipelineState state, int cameraId) {
        Map<String, Object> h = state.capture().header();
        Map<String, Object> fail = new java.util.LinkedHashMap<>();
        fail.put("status", "FAIL");
        fail.put("overallPass", false);
        fail.put("alignmentPass", false);
        fail.put("camera_id", cameraId);
        fail.put("frame_id", h.get("frame_id"));
        fail.put("error", "positioning reject");
        double shiftX = YamlScalars.toDouble(h.getOrDefault("positioning_shift_x_mm", 9999.0), 9999.0);
        double shiftY = YamlScalars.toDouble(h.getOrDefault("positioning_shift_y_mm", 9999.0), 9999.0);
        fail.put("shiftXmm", shiftX);
        fail.put("shiftYmm", shiftY);
        fail.put("deviationRadiusMm", Math.hypot(shiftX, shiftY));
        fail.put("rotationDeg", h.getOrDefault("positioning_rotation_deg", 9999.0));
        Object homography = h.get("positioning_homography_ref_to_cur");
        fail.put("homographyRefToCurrent", homography == null ? List.of() : homography);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, fail, new byte[0]);
    }
}
