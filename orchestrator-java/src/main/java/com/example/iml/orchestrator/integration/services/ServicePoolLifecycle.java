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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Запуск пулов дочерних сервисов по команде из YAML и пулов потоков стадий пайплайна (capture/geometry/python/decision).
 */
public final class ServicePoolLifecycle {

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
        List<String> cmd = List.copyOf(command);
        if (poolSize == 1) {
            tryStartMember(pool, label, cmd, projectRoot, commandTimeoutMs);
            return pool;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(poolSize, 8), r -> {
            Thread t = new Thread(r, label + "-boot");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<ServiceProcessSupervisor>> futures = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                String serviceName = label + "-" + i;
                futures.add(executor.submit(() -> tryCreateMember(serviceName, cmd, projectRoot, commandTimeoutMs)));
            }
            for (Future<ServiceProcessSupervisor> future : futures) {
                try {
                    ServiceProcessSupervisor started = future.get();
                    if (started != null) {
                        pool.add(started);
                    }
                } catch (Exception e) {
                    log.warn("failed to join optional {} start: {}", label, e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return pool;
    }

    private void tryStartMember(
            List<ServiceProcessSupervisor> pool,
            String serviceName,
            List<String> cmd,
            Path projectRoot,
            int commandTimeoutMs
    ) {
        ServiceProcessSupervisor started = tryCreateMember(serviceName, cmd, projectRoot, commandTimeoutMs);
        if (started != null) {
            pool.add(started);
        }
    }

    private ServiceProcessSupervisor tryCreateMember(
            String serviceName,
            List<String> cmd,
            Path projectRoot,
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
