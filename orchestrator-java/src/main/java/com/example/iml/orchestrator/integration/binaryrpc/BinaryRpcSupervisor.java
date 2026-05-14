package com.example.iml.orchestrator.integration.binaryrpc;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.util.Map;

/**
 * Дочерний процесс с бинарным RPC: единый контракт для camera-worker и сервисных пулов (ISP).
 * Реализации: {@link com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor},
 * {@link com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor}.
 */
public interface BinaryRpcSupervisor extends AutoCloseable {

    BinaryProtocol.Message command(Map<String, Object> header) throws IOException;

    BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException;

    BinaryProtocol.Message health() throws IOException;

    void start() throws IOException;

    void restart() throws IOException;

    int restartCount();

    /** Метка для логов (имя сервиса или идентификатор воркера камеры). */
    String supervisorLabel();

    @Override
    void close();
}
