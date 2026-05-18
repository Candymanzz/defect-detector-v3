package com.example.iml.orchestrator.integration.services;

import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
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
        if (command == null || command.isEmpty()) {
            return pool;
        }
        List<String> cmd = new ArrayList<>(command);
        for (int i = 0; i < poolSize; i++) {
            String serviceName = poolSize == 1 ? label : (label + "-" + i);
            try {
                ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(serviceName, cmd, projectRoot, commandTimeoutMs);
                supervisor.start();
                BinaryProtocol.Message health = supervisor.health();
                log.info("{} health => {}", serviceName, health.header());
                pool.add(supervisor);
            } catch (Exception e) {
                log.warn("failed to start optional {} service command={}: {}", serviceName, command, e.getMessage());
            }
        }
        return pool;
    }

    /**
     * Пул HTTP-клиентов к FastAPI analisSurface (без subprocess stdio).
     */
    public List<BinaryRpcSupervisor> startAnalisSurfaceHttpPool(
            String baseUrl,
            int poolSize,
            int commandTimeoutMs
    ) {
        List<BinaryRpcSupervisor> pool = new ArrayList<>();
        if (baseUrl == null || baseUrl.isBlank()) {
            return pool;
        }
        int n = Math.max(1, poolSize);
        for (int i = 0; i < n; i++) {
            String name = n == 1 ? "analis-surface-http" : ("analis-surface-http-" + i);
            AnalisSurfaceHttpBinaryRpcSupervisor supervisor =
                    new AnalisSurfaceHttpBinaryRpcSupervisor(name, baseUrl, commandTimeoutMs);
            try {
                supervisor.start();
                BinaryProtocol.Message health = supervisor.health();
                log.info("{} health => {}", name, health.header());
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
