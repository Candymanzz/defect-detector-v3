package com.example.iml.orchestrator.integration.pipeline.stages;

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
            Map<String, Object> pyHeader = BinaryInspectHeaders.pythonInspectHeader(
                    cameraId, productType, detectorId, state.capture(), state.geom(), pythonCfg, false, activeReference);
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
            if (inspectionRuntimeConfig != null) {
                inspectionRuntimeConfig.applyToPythonHeader(pyHeader, pythonCfg, productType);
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
