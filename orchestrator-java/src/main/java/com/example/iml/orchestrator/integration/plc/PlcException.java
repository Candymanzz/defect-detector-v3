package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * PLC / FINS failures.
 */
public class PlcException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public PlcException(String message) {
        super(message);
    }

    public PlcException(String message, Throwable cause) {
        super(message, cause);
    }

    public PlcException(Throwable cause) {
        super(cause);
    }
}
