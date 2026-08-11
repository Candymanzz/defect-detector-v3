package com.example.iml.orchestrator.integration.clientws.session;

/**
 * Состояние сессии клиента. До приёма эталонов — {@link #NO_REFERENCE}.
 * {@link #TEST} — ручной прогон geometry/python/настроек/FP без прод-триггера и PLC ready.
 */
public enum ClientWsSessionState {
    NO_REFERENCE,
    READY,
    OPERATIONAL,
    /** Режим теста настроек: DI3-инспекция остановлена, UI гоняет test-analyze. */
    TEST
}
