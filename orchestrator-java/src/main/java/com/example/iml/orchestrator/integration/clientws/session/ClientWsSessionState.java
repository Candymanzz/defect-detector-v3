package com.example.iml.orchestrator.integration.clientws.session;

/**
 * Состояние сессии клиента. До приёма эталонов — {@link #NO_REFERENCE}.
 */
public enum ClientWsSessionState {
    NO_REFERENCE,
    READY,
    OPERATIONAL
}
