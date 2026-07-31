package com.example.iml.orchestrator.integration.capture;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Capture / line-sync failures.
 */
public class CaptureException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public CaptureException(String message) {
        super(message);
    }

    public CaptureException(String message, Throwable cause) {
        super(message, cause);
    }

    public CaptureException(Throwable cause) {
        super(cause);
    }
}
