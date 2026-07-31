package com.example.iml.orchestrator;

/**
 * Unchecked base for runtime orchestrator failures.
 */
public class OrchestratorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrchestratorException(String message) {
        super(message);
    }

    public OrchestratorException(String message, Throwable cause) {
        super(message, cause);
    }

    public OrchestratorException(Throwable cause) {
        super(cause);
    }
}
