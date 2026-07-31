package com.example.iml.orchestrator;

/**
 * Доменная ошибка запуска оркестратора (конфиг, инициализация, стартовая валидация).
 */
public final class OrchestratorStartupException extends Exception {

    private static final long serialVersionUID = 1L;

    public OrchestratorStartupException(String message) {
        super(message);
    }

    public OrchestratorStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
