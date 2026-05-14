package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.util.Map;

/** Бинарный RPC к дочернему процессу (stdio или named pipe). */
public interface BinaryClient extends AutoCloseable {
    BinaryProtocol.Message command(Map<String, Object> header) throws IOException;

    BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException;

    boolean isAlive();

    @Override
    void close();
}
