package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Trigger / UDP / DI failures.
 */
public class TriggerException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public TriggerException(String message) {
        super(message);
    }

    public TriggerException(String message, Throwable cause) {
        super(message, cause);
    }

    public TriggerException(Throwable cause) {
        super(cause);
    }
}
