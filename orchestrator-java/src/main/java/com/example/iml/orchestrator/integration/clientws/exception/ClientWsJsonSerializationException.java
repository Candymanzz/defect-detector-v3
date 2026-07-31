package com.example.iml.orchestrator.integration.clientws.exception;

/**
 * Ошибка сериализации/десериализации JSON для WebSocket-протокола.
 */
public final class ClientWsJsonSerializationException extends ClientWsException {

    private static final long serialVersionUID = 1L;

    public ClientWsJsonSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
