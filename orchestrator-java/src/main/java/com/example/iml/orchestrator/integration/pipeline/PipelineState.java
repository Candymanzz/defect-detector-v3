package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

/** Состояние после capture / python / geometry. */
public record PipelineState(
        BinaryProtocol.Message capture,
        BinaryProtocol.Message py,
        BinaryProtocol.Message geom,
        long captureMs,
        long pythonMs,
        long geometryMs
) {
}
