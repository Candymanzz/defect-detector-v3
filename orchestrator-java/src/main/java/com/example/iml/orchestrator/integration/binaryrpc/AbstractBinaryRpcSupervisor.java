package com.example.iml.orchestrator.integration.binaryrpc;

import com.example.iml.orchestrator.integration.camera.BinaryClient;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.example.iml.orchestrator.integration.logging.PerformanceTrace;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Общая реализация: один поток вызовов, таймаут, закрытие клиента, {@link #restart()}.
 */
public abstract class AbstractBinaryRpcSupervisor {

    protected final ExecutorService callExecutor;
    protected final int commandTimeoutMs;
    protected BinaryClient client;
    protected int restartCount;
    private volatile Consumer<Boolean> healthListener;

    protected AbstractBinaryRpcSupervisor(int commandTimeoutMs, ThreadFactory threadFactory) {
        this.commandTimeoutMs = Math.max(100, commandTimeoutMs);
        this.callExecutor = Executors.newSingleThreadExecutor(threadFactory);
    }

    protected abstract String supervisorLabel();

    public abstract void start() throws IOException;

    public abstract BinaryProtocol.Message command(Map<String, Object> header) throws IOException;

    public final BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException {
        ensureAlive();
        return awaitCommandOnClient(header);
    }

    public BinaryProtocol.Message health() throws IOException {
        return command(Map.of("op", "health"));
    }

    public void restart() throws IOException {
        restartCount++;
        closeClientOnly();
        start();
    }

    public int restartCount() {
        return restartCount;
    }

    public void setHealthListener(Consumer<Boolean> healthListener) {
        this.healthListener = healthListener;
    }

    public boolean processAlive() {
        return client != null && client.isAlive();
    }

    protected final void reportHealthy() {
        Consumer<Boolean> listener = healthListener;
        if (listener != null) {
            try {
                listener.accept(true);
            } catch (Exception ignored) {
            }
        }
    }

    protected final void reportUnhealthy() {
        Consumer<Boolean> listener = healthListener;
        if (listener != null) {
            try {
                listener.accept(false);
            } catch (Exception ignored) {
            }
        }
    }

    public void close() {
        closeClientOnly();
        callExecutor.shutdownNow();
    }

    protected abstract void ensureAlive() throws IOException;

    protected final BinaryProtocol.Message awaitCommandOnClient(Map<String, Object> header) throws IOException {
        long enqueued = System.nanoTime();
        Map<String, Object> queuedFields = PerformanceTrace.context(header);
        queuedFields.put("operation", header.get("op"));
        PerformanceTrace.emit(supervisorLabel(), "binary_rpc_queued", queuedFields);
        CompletableFuture<BinaryProtocol.Message> future = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            BinaryProtocol.Message response = null;
            try {
                response = client.command(header);
                return response;
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                Map<String, Object> fields = PerformanceTrace.context(header);
                if (response != null) PerformanceTrace.copyContext(response.header(), fields);
                fields.put("operation", header.get("op"));
                fields.put("queue_ms", (started - enqueued) / 1_000_000.0);
                fields.put("transport_ms", PerformanceTrace.elapsedMs(started));
                fields.put("response_type", response == null ? "exception" : response.type());
                Map<String, Object> service = new java.util.LinkedHashMap<>();
                if (response != null) response.header().forEach((key, value) -> {
                    if ((key.startsWith("stage_ms_") || key.startsWith("capture_")) && value instanceof Number) service.put(key, value);
                });
                fields.put("service_metrics", service);
                PerformanceTrace.emit(supervisorLabel(), "binary_rpc", fields);
            }
        }, callExecutor);
        try {
            return future.get(commandTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException(supervisorLabel() + " command timeout after " + commandTimeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re && re.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(supervisorLabel() + " command failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(supervisorLabel() + " interrupted", e);
        }
    }

    protected final void closeClientOnly() {
        if (client != null) {
            try {
                client.command(Map.of("op", "stop"));
            } catch (Exception ignored) {
            }
            client.close();
            client = null;
        }
    }
}
