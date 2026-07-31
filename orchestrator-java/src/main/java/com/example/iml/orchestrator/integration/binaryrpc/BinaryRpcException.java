package com.example.iml.orchestrator.integration.binaryrpc;

import com.example.iml.orchestrator.OrchestratorException;

/**
 * Binary RPC / worker IPC failures.
 */
public class BinaryRpcException extends OrchestratorException {

    private static final long serialVersionUID = 1L;

    public BinaryRpcException(String message) {
        super(message);
    }

    public BinaryRpcException(String message, Throwable cause) {
        super(message, cause);
    }

    public BinaryRpcException(Throwable cause) {
        super(cause);
    }
}
