package com.example.iml.orchestrator.integration.subprocess;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * External process failures.
 */
public class SubprocessException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public SubprocessException(String message) {
        super(message);
    }

    public SubprocessException(String message, Throwable cause) {
        super(message, cause);
    }

    public SubprocessException(Throwable cause) {
        super(cause);
    }
}
