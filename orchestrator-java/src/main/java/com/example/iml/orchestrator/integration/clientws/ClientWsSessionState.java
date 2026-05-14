package com.example.iml.orchestrator.integration.clientws;

/**
 * Состояние сессии клиента (Фаза 0 контракта). До приёма эталонов — {@link #NO_REFERENCE}.
 * {@link #READY} — эталоны приняты (Фаза 3: можно ждать первый кадр инспекции перед полным OPERATIONAL).
 */
public enum ClientWsSessionState {
    NO_REFERENCE,
    READY,
    OPERATIONAL
}
