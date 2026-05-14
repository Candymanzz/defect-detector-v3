package com.example.iml.orchestrator.integration.binaryrpc;

import com.example.iml.orchestrator.integration.camera.BinaryClient;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Общая реализация: один поток вызовов, таймаут, закрытие клиента, {@link #restart()}.
 */
public abstract class AbstractBinaryRpcSupervisor {

    protected final ExecutorService callExecutor;
    protected final int commandTimeoutMs;
    protected BinaryClient client;
    protected int restartCount;

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

    public void close() {
        closeClientOnly();
        callExecutor.shutdownNow();
    }

    protected abstract void ensureAlive() throws IOException;

    protected final BinaryProtocol.Message awaitCommandOnClient(Map<String, Object> header) throws IOException {
        CompletableFuture<BinaryProtocol.Message> future = CompletableFuture.supplyAsync(() -> {
            try {
                return client.command(header);
            } catch (IOException e) {
                throw new RuntimeException(e);
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
