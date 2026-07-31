package com.example.iml.orchestrator.integration.stream;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Stream / preview failures.
 */
public class StreamException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public StreamException(String message) {
        super(message);
    }

    public StreamException(String message, Throwable cause) {
        super(message, cause);
    }

    public StreamException(Throwable cause) {
        super(cause);
    }
}
