package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineException;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

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
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg
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
            long t0 = System.nanoTime();
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
                long wallMs = YamlScalars.nanosToMs(System.nanoTime() - t0);
                InspectPositioningTiming.log(log, cameraId, state, resp, wallMs);
                return InspectPositioningResponseApplier.apply(log, failOnReject, state, resp, cameraId, wallMs);
            } finally {
                if (positioningSlots != null) {
                    positioningSlots.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PipelineException(e);
        }
    }
}
