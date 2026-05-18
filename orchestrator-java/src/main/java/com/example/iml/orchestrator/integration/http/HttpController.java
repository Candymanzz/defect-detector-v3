package com.example.iml.orchestrator.integration.http;

import java.io.IOException;

/**
 * Обработчик одного HTTP-маршрута (паттерн Front Controller → Controller).
 */
@FunctionalInterface
public interface HttpController {

    void handle(HttpRequestContext ctx) throws IOException;
}
