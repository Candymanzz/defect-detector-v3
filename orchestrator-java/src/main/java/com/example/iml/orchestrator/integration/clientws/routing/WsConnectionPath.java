package com.example.iml.orchestrator.integration.clientws.routing;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;

/**
 * Проверка path при WebSocket handshake.
 */
public final class WsConnectionPath {

    private WsConnectionPath() {
    }

    public static boolean allowed(ClientWsConfig cfg, String resourceDescriptor) {
        String p = resourceDescriptor == null ? "" : resourceDescriptor.trim();
        if (p.isEmpty()) {
            p = "/";
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        String configured = cfg.path();
        if ("/".equals(configured)) {
            return p.startsWith("/");
        }
        return configured.equals(p);
    }
}
