package com.example.iml.orchestrator.integration.gpio;

/** Чтение одного дискретного входа (0/1). */
public interface DigitalInputReader extends AutoCloseable {

    /** {@code true} — логическая «1» на входе. */
    boolean readActive() throws Exception;

    @Override
    default void close() {
    }
}
