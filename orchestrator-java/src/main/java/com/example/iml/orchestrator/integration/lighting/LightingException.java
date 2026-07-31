package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Lighting / flash failures.
 */
public class LightingException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public LightingException(String message) {
        super(message);
    }

    public LightingException(String message, Throwable cause) {
        super(message, cause);
    }

    public LightingException(Throwable cause) {
        super(cause);
    }
}
