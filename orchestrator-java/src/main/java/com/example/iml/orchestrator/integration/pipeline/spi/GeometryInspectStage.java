package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Стадия geometry: один кадр → ответ сервиса (ISP: только эта операция). */
public interface GeometryInspectStage {

    PipelineState apply(
            PipelineState state,
            int cameraId,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            List<? extends BinaryRpcSupervisor> geometryPool,
            Semaphore geometrySlots,
            AtomicInteger geometryRoundRobin
    );
}
