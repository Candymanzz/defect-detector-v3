package com.example.iml.orchestrator.integration.gpio;

/** Источник состояния DI (работа / направление / триггер). */
public interface DiscreteInputSnapshotSource extends AutoCloseable {

    DiscreteInputSnapshot readSnapshot() throws Exception;

    @Override
    default void close() {
    }
}
