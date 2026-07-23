package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

/**
 * Обёртка над уже запущенным {@link AutoCloseable}: {@link #start()} — no-op.
 */
public final class CloseableIntegrationComponent implements IntegrationComponent {

    private final AutoCloseable closeable;

    public CloseableIntegrationComponent(AutoCloseable closeable) {
        this.closeable = closeable;
    }

    public static IntegrationComponent ofNullable(AutoCloseable closeable) {
        return closeable == null ? null : new CloseableIntegrationComponent(closeable);
    }

    @Override
    public void start() {
        // already started by owning service
    }

    @Override
    public void close() {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
