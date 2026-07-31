package com.example.iml.orchestrator.integration.clientws.exception;

/**
 * Не удалось отправить исходящее WebSocket-сообщение.
 */
public final class ClientWsSendFailedException extends ClientWsException {

    private static final long serialVersionUID = 1L;

    public ClientWsSendFailedException(String messageType, Throwable cause) {
        super("failed to send " + messageType, cause);
    }
}
