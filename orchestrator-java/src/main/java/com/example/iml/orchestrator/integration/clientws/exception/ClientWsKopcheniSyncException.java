package com.example.iml.orchestrator.integration.clientws.exception;

/**
 * Ошибка синхронизации эталона/FP с пулом analisSurface (HTTP binary RPC).
 */
public final class ClientWsKopcheniSyncException extends ClientWsException {

    public ClientWsKopcheniSyncException(String message) {
        super(message);
    }

    public ClientWsKopcheniSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
