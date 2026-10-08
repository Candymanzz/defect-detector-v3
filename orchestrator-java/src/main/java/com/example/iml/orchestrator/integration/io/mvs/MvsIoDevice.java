package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import java.util.Map;
import java.util.function.Consumer;

/** One controller connection. Callbacks must only enqueue events, never call the SDK. */
public interface MvsIoDevice extends AutoCloseable {
    void open();
    Map<Integer, Boolean> readInputs();
    default void checkConnection() { readInputs(); }
    void subscribe(Consumer<IoInputDiChange> listener);
    Map<Integer, Boolean> readOutputs();
    default void setOutput(int port, boolean high) { throw new UnsupportedOperationException("DO writes unavailable for this backend"); }
    default void triggerTimer(String timer, String line) { throw new UnsupportedOperationException("Software timer commands unavailable for this backend"); }
    @Override void close();
}
