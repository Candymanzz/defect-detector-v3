package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Выравнивание кадра к эталону перед geometry/surface: ведро всегда в одной позе.
 */
public final class InspectPositioningExecutor {

    public static final String HEADER_HARD_FAIL = "positioning_hard_fail";
    public static final String HEADER_ALIGNED = "positioning_aligned";

    private final Logger log;
    private final List<? extends BinaryRpcSupervisor> positioningPool;
    private final Semaphore positioningSlots;
    private final AtomicInteger positioningRoundRobin;
    private final Map<String, Object> positioningCfg;
    private final boolean enabled;
    private final boolean failOnReject;

    public InspectPositioningExecutor(
            Logger log,
            List<? extends BinaryRpcSupervisor> positioningPool,
            Semaphore positioningSlots,
            AtomicInteger positioningRoundRobin,
            Map<String, Object> positioningCfg
    ) {
        this.log = log;
        this.positioningPool = positioningPool == null ? List.of() : List.copyOf(positioningPool);
        this.positioningSlots = positioningSlots;
        this.positioningRoundRobin = positioningRoundRobin == null ? new AtomicInteger() : positioningRoundRobin;
        this.positioningCfg = positioningCfg == null ? Map.of() : Map.copyOf(positioningCfg);
        this.enabled = YamlScalars.toBool(this.positioningCfg.get("enabled"), !this.positioningPool.isEmpty())
                && !this.positioningPool.isEmpty();
        // Large pose discrepancy is expected; hard-fail only when alignment itself failed.
        this.failOnReject = YamlScalars.toBool(this.positioningCfg.get("fail_on_reject"), true);
    }

    public static InspectPositioningExecutor disabled(Logger log) {
        return new InspectPositioningExecutor(log, List.of(), new Semaphore(0), new AtomicInteger(), Map.of("enabled", false));
    }

    public PipelineState apply(
            PipelineState state,
            int cameraId,
            String productType,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg
    ) {
        if (!enabled) {
            return state;
        }
        if (activeReference == null || activeReference.header() == null) {
            return state;
        }
        if (state == null || state.capture() == null || state.capture().header() == null) {
            return state;
        }
        BinaryRpcSupervisor positioning = positioningPool.get(
                Math.floorMod(positioningRoundRobin.getAndIncrement(), positioningPool.size())
        );
        try {
            Map<String, Object> header = BinaryInspectHeaders.positioningHeader(
                    cameraId,
                    state.capture(),
                    activeReference,
                    geometryCfg,
                    positioningCfg
            );
            BinaryInspectHeaders.applyMainRoiFromPolygon(header, state.capture(), activeReference);
            if (positioningSlots != null) {
                positioningSlots.acquire();
            }
            try {
                BinaryProtocol.Message resp = positioning.command(header);
                if (log.isDebugEnabled()) {
                    log.debug("positioning cam={} frame={} => {}", cameraId, state.capture().header().get("frame_id"), resp.header());
                }
                return applyResponse(state, resp, cameraId);
            } finally {
                if (positioningSlots != null) {
                    positioningSlots.release();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PipelineState applyResponse(PipelineState state, BinaryProtocol.Message resp, int cameraId) {
        Map<String, Object> captureHeader = new LinkedHashMap<>(state.capture().header());
        boolean ok = resp != null
                && resp.type() == BinaryProtocol.MSG_RESPONSE
                && YamlScalars.toBool(resp.header().get("overallPass"), false);
        boolean alignedWritten = resp != null
                && resp.type() == BinaryProtocol.MSG_RESPONSE
                && YamlScalars.toBool(resp.header().get("alignedWritten"), false);

        if (resp != null && resp.header() != null) {
            captureHeader.put("positioning_status", resp.header().get("status"));
            captureHeader.put("positioning_shift_x_mm", resp.header().get("shiftXmm"));
            captureHeader.put("positioning_shift_y_mm", resp.header().get("shiftYmm"));
            captureHeader.put("positioning_rotation_deg", resp.header().get("rotationDeg"));
            Object h = resp.header().get("homographyRefToCurrent");
            if (h != null) {
                captureHeader.put("positioning_homography_ref_to_cur", h);
            }
        }

        if (alignedWritten && resp != null) {
            String outName = String.valueOf(resp.header().getOrDefault("output_shm_name", "")).trim();
            if (outName.isEmpty()) {
                outName = String.valueOf(resp.header().getOrDefault("shm_name", "")).trim();
            }
            if (!outName.isEmpty()) {
                captureHeader.put("original_shm_name", state.capture().header().get("shm_name"));
                captureHeader.put("shm_name", outName.startsWith("/") ? outName : "/" + outName.replace("/", "_"));
                captureHeader.put("shm_offset", 0);
                captureHeader.put("width", resp.header().get("width"));
                captureHeader.put("height", resp.header().get("height"));
                captureHeader.put("stride", resp.header().get("stride"));
                captureHeader.put(HEADER_ALIGNED, true);
            }
        }

        if (!ok && failOnReject) {
            captureHeader.put(HEADER_HARD_FAIL, true);
            if (log != null) {
                log.info(
                        "positioning reject cam={} frame={} shift=({}, {}) rot={} status={}",
                        cameraId,
                        captureHeader.get("frame_id"),
                        captureHeader.get("positioning_shift_x_mm"),
                        captureHeader.get("positioning_shift_y_mm"),
                        captureHeader.get("positioning_rotation_deg"),
                        captureHeader.get("positioning_status")
                );
            }
        }

        BinaryProtocol.Message remappedCapture = new BinaryProtocol.Message(
                state.capture().type(),
                Map.copyOf(captureHeader),
                state.capture().payload()
        );
        return new PipelineState(
                remappedCapture,
                state.py(),
                state.geom(),
                state.captureMs(),
                state.pythonMs(),
                state.geometryMs()
        );
    }
}
