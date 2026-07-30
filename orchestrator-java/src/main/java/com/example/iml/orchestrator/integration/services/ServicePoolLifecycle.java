package com.example.iml.orchestrator.integration.services;

import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.python.AnalisSurfacePoolSupport;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Запуск пулов дочерних сервисов по команде из YAML и пулов потоков стадий пайплайна (capture/geometry/python/decision).
 */
public final class ServicePoolLifecycle {

    private static final AtomicInteger POOL_START_SEQ = new AtomicInteger();

    private final Logger log;

    public ServicePoolLifecycle(Logger log) {
        this.log = log;
    }

    public List<ServiceProcessSupervisor> startOptionalPool(
            List<String> command,
            Path projectRoot,
            String label,
            int commandTimeoutMs,
            int poolSize
    ) {
        List<ServiceProcessSupervisor> pool = new ArrayList<>();
        if (command == null || command.isEmpty() || poolSize <= 0) {
            return pool;
        }
        List<String> cmd = new ArrayList<>(command);
        if (poolSize == 1) {
            startOneOptional(pool, cmd, projectRoot, label, commandTimeoutMs);
            return pool;
        }

        ExecutorService startPool = Executors.newFixedThreadPool(
                poolSize,
                r -> {
                    Thread t = new Thread(r, label + "-start-" + POOL_START_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
        );
        List<CompletableFuture<ServiceProcessSupervisor>> futures = new ArrayList<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                String serviceName = label + "-" + i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> startOneOptionalSupervisor(cmd, projectRoot, serviceName, commandTimeoutMs),
                        startPool
                ));
            }
            for (CompletableFuture<ServiceProcessSupervisor> future : futures) {
                try {
                    ServiceProcessSupervisor supervisor = future.join();
                    if (supervisor != null) {
                        pool.add(supervisor);
                    }
                } catch (CompletionException e) {
                    // already logged in startOneOptionalSupervisor
                }
            }
        } finally {
            startPool.shutdownNow();
            try {
                startPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return pool;
    }

    private void startOneOptional(
            List<ServiceProcessSupervisor> pool,
            List<String> cmd,
            Path projectRoot,
            String serviceName,
            int commandTimeoutMs
    ) {
        ServiceProcessSupervisor supervisor = startOneOptionalSupervisor(cmd, projectRoot, serviceName, commandTimeoutMs);
        if (supervisor != null) {
            pool.add(supervisor);
        }
    }

    private ServiceProcessSupervisor startOneOptionalSupervisor(
            List<String> cmd,
            Path projectRoot,
            String serviceName,
            int commandTimeoutMs
    ) {
        try {
            ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(serviceName, cmd, projectRoot, commandTimeoutMs);
            supervisor.start();
            BinaryProtocol.Message health = supervisor.health();
            log.info("{} health => {}", serviceName, health.header());
            return supervisor;
        } catch (Exception e) {
            log.warn("failed to start optional {} service command={}: {}", serviceName, cmd, e.getMessage());
            return null;
        }
    }

    /**
     * HTTP-клиенты пайплайна: {@code clientCount} штук, round-robin по {@code serverBaseUrls}.
     */
    public List<BinaryRpcSupervisor> startAnalisSurfaceHttpPool(
            List<String> serverBaseUrls,
            int clientCount,
            int commandTimeoutMs
    ) {
        List<BinaryRpcSupervisor> pool = new ArrayList<>();
        List<String> clientUrls = AnalisSurfacePoolSupport.clientBaseUrls(serverBaseUrls, clientCount);
        if (clientUrls.isEmpty()) {
            return pool;
        }
        int servers = serverBaseUrls == null ? 0 : serverBaseUrls.size();
        for (int i = 0; i < clientUrls.size(); i++) {
            String baseUrl = clientUrls.get(i);
            if (baseUrl == null || baseUrl.isBlank()) {
                continue;
            }
            int serverIndex = servers <= 0 ? 0 : (i % servers);
            String name = clientUrls.size() == 1
                    ? "analis-surface-http"
                    : ("analis-surface-http-" + i + "->srv" + serverIndex);
            AnalisSurfaceHttpBinaryRpcSupervisor supervisor =
                    new AnalisSurfaceHttpBinaryRpcSupervisor(name, baseUrl, commandTimeoutMs);
            try {
                supervisor.start();
                BinaryProtocol.Message health = supervisor.health();
                log.info("{} health => {} base_url={}", name, health.header(), baseUrl);
                pool.add(supervisor);
            } catch (IOException e) {
                log.warn("failed to start {} baseUrl={}: {}", name, baseUrl, e.getMessage());
            }
        }
        return pool;
    }

    public ExecutorService newStageExecutor(String name, int threads, int queueSize) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(1, threads),
                Math.max(1, threads),
                30L,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(Math.max(1, queueSize)),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    public void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
    }
}
