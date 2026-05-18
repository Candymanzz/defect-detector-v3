package com.example.iml.orchestrator.integration.http.exception;

/**
 * Ошибка разбора path/query/body HTTP-запроса.
 */
public final class HttpRequestParseException extends HttpHandlerException {

    public HttpRequestParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
