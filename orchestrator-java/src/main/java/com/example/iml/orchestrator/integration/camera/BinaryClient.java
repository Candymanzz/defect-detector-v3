package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.util.Map;

/** Command client for a native camera or a child service. */
public interface BinaryClient extends AutoCloseable {
    BinaryProtocol.Message command(Map<String, Object> header) throws IOException;

    BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException;

    boolean isAlive();

    /** Signal shutdown before waiting for in-flight calls to release resources. */
    default void requestStop() throws IOException {
        command(Map.of("op", "stop"));
    }

    @Override
    void close();
}
