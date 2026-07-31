package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * UI / artifacts / archive failures.
 */
public class UiException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public UiException(String message) {
        super(message);
    }

    public UiException(String message, Throwable cause) {
        super(message, cause);
    }

    public UiException(Throwable cause) {
        super(cause);
    }
}
