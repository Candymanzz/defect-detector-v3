package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Bootstrap / lifecycle failures.
 */
public class BootstrapException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public BootstrapException(String message) {
        super(message);
    }

    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }

    public BootstrapException(Throwable cause) {
        super(cause);
    }
}
