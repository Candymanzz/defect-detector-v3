package com.example.iml.orchestrator.integration.lighting.nativeio;

public interface LightDevice extends AutoCloseable {
    void open();
    void brightness(int channel, int value);
    void source(int channel, boolean on);
    default void sources(java.util.List<Integer> channels, boolean on) {
        RuntimeException failure = null;
        for (int channel : channels) try { source(channel, on); } catch (RuntimeException e) { failure = e; if (on) break; }
        if (failure != null) throw failure;
    }
    void checkConnection();
    @Override void close();
}
