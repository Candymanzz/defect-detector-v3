package com.example.iml.orchestrator.integration.clientws.routing;

/**
 * Обработчик одного входящего WebSocket-сообщения (Front Controller → Handler).
 */
@FunctionalInterface
public interface WsMessageHandler {

    void handle(WsMessageContext ctx) throws com.example.iml.orchestrator.integration.clientws.exception.ClientWsException;
}
