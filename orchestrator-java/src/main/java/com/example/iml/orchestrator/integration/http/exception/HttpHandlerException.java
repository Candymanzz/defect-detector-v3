package com.example.iml.orchestrator.integration.http.exception;

/**
 * Базовое исключение HTTP-обработчиков оркестратора.
 */
public class HttpHandlerException extends Exception {

    public HttpHandlerException(String message, Throwable cause) {
        super(message, cause);
    }
}
