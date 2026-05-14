package com.example.iml.orchestrator.integration.clientws.bundle;

/**
 * Ошибка разбора пакета эталонов / FP (код для {@code server.error}).
 */
public final class BundleParseException extends Exception {

    private final String code;

    public BundleParseException(String code, String message) {
        super(message);
        this.code = code == null ? "parse_error" : code;
    }

    public String code() {
        return code;
    }
}
