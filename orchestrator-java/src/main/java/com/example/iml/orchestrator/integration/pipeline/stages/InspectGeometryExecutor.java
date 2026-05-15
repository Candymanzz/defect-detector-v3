package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.GeometryInspectStage;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.GeometryWsBundleMerge;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Вызов geometry-сервиса для текущего кадра (семафор пула, round-robin).
 */
public final class InspectGeometryExecutor implements GeometryInspectStage {

    private final Logger log;
    private final GeometrySnapshotCache geometrySnapshotCache;
    private final GeometryRuntimeConfig geometryRuntimeConfig;
    private final ClientWsReferenceContext wsReferenceContext;
    private final int geometryReferenceViewIndex;

    public InspectGeometryExecutor(Logger log) {
        this(log, null, null, null, -1);
    }

    public InspectGeometryExecutor(Logger log, GeometrySnapshotCache geometrySnapshotCache) {
        this(log, geometrySnapshotCache, null, null, -1);
    }

    public InspectGeometryExecutor(
            Logger log,
            GeometrySnapshotCache geometrySnapshotCache,
            GeometryRuntimeConfig geometryRuntimeConfig
    ) {
        this(log, geometrySnapshotCache, geometryRuntimeConfig, null, -1);
    }

    public InspectGeometryExecutor(
            Logger log,
            GeometrySnapshotCache geometrySnapshotCache,
            GeometryRuntimeConfig geometryRuntimeConfig,
            ClientWsReferenceContext wsReferenceContext,
            int geometryReferenceViewIndex
    ) {
        this.log = log;
        this.geometrySnapshotCache = geometrySnapshotCache;
        this.geometryRuntimeConfig = geometryRuntimeConfig;
        this.wsReferenceContext = wsReferenceContext;
        this.geometryReferenceViewIndex = geometryReferenceViewIndex < -1 || geometryReferenceViewIndex > 4 ? -1 : geometryReferenceViewIndex;
    }

    @Override
    public PipelineState apply(
            PipelineState state,
            int cameraId,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            List<? extends BinaryRpcSupervisor> geometryPool,
            Semaphore geometrySlots,
            AtomicInteger geometryRoundRobin
    ) {
        if (geometryPool.isEmpty()) {
            return state;
        }
        BinaryRpcSupervisor geometry = geometryPool.get(Math.floorMod(geometryRoundRobin.getAndIncrement(), geometryPool.size()));
        try {
            long t0 = System.nanoTime();
            Map<String, Object> gHeader = BinaryInspectHeaders.geometryInspectHeader(cameraId, state.capture(), activeReference, geometryCfg);
            GeometryWsBundleMerge.applyCommittedBundleToGeometryHeader(
                    wsReferenceContext,
                    cameraId,
                    geometryReferenceViewIndex,
                    gHeader,
                    log
            );
            if (geometryRuntimeConfig != null) {
                geometryRuntimeConfig.applyToGeometryHeader(gHeader);
            }
            geometrySlots.acquire();
            try {
                BinaryProtocol.Message geomResp = geometry.command(gHeader);
                if (log.isDebugEnabled()) {
                    Map<String, Object> gh = geomResp.header();
                    log.debug(
                            "{} cam={} frame={} geom_type={} geom_header_keys={}",
                            geometry.supervisorLabel(),
                            cameraId,
                            state.capture().header().get("frame_id"),
                            geomResp.type(),
                            gh == null ? List.of() : gh.keySet()
                    );
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
}
