package com.example.iml.orchestrator.integration.clientws.exception;

/**
 * Не удалось отправить исходящее WebSocket-сообщение.
 */
public final class ClientWsSendFailedException extends ClientWsException {

    public ClientWsSendFailedException(String messageType, Throwable cause) {
        super("failed to send " + messageType, cause);
    }
}
