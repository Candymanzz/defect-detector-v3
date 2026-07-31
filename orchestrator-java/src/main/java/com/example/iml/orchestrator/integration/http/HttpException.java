package com.example.iml.orchestrator.integration.http;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * HTTP layer failures.
 */
public class HttpException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public HttpException(String message) {
        super(message);
    }

    public HttpException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpException(Throwable cause) {
        super(cause);
    }
}
