package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
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

    public InspectPythonExecutor(Logger log) {
        this.log = log;
    }

    @Override
    public PipelineState apply(
            PipelineState state,
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> pythonCfg,
            List<? extends BinaryRpcSupervisor> pythonPool,
            Semaphore pythonSlots,
            AtomicInteger pythonRoundRobin
    ) {
        if (pythonPool.isEmpty()) {
            return state;
        }
        BinaryRpcSupervisor python = pythonPool.get(Math.floorMod(pythonRoundRobin.getAndIncrement(), pythonPool.size()));
        try {
            long t0 = System.nanoTime();
            Map<String, Object> pyHeader = BinaryInspectHeaders.pythonInspectHeader(
                    cameraId, productType, detectorId, state.capture(), state.geom(), pythonCfg, false);
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
}
