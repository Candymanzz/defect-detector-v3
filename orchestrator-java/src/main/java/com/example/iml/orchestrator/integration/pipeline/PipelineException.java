package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Inspection pipeline failures.
 */
public class PipelineException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }

    public PipelineException(Throwable cause) {
        super(cause);
    }
}
