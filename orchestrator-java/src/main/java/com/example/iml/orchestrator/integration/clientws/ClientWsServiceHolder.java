package com.example.iml.orchestrator.integration.clientws;

/**
 * Отложенная привязка {@link ClientWebSocketServer} к HTTP (mount поднимается раньше WS).
 */
public final class ClientWsServiceHolder {

    private volatile ClientWebSocketServer server;

    public ClientWebSocketServer get() {
        return server;
    }

    public void set(ClientWebSocketServer server) {
        this.server = server;
    }
}
