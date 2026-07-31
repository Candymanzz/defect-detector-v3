package com.example.iml.orchestrator.integration.clientws.exception;

/**
 * Базовое исключение WebSocket-слоя оркестратора.
 */
public class ClientWsException extends Exception {

    private static final long serialVersionUID = 1L;

    public ClientWsException(String message) {
        super(message);
    }

    public ClientWsException(String message, Throwable cause) {
        super(message, cause);
    }
}
