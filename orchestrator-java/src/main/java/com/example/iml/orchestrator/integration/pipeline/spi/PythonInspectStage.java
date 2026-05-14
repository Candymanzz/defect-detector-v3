package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Стадия python-детектора: состояние после geometry → ответ python. */
public interface PythonInspectStage {

    PipelineState apply(
            PipelineState state,
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> pythonCfg,
            List<? extends BinaryRpcSupervisor> pythonPool,
            Semaphore pythonSlots,
            AtomicInteger pythonRoundRobin
    );
}
