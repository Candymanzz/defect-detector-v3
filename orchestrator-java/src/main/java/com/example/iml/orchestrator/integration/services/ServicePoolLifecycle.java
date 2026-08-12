package com.example.iml.orchestrator.integration.services;

import com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.python.AnalisSurfacePoolSupport;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * Стартует пул последовательно: параллельный boot 10× OpenCV на Windows ломает
     * {@code nu.pattern.OpenCV.loadLocally()} (общий temp + deleteOldInstancesOnStart).
     * Каждому Java-воркеру — свой {@code java.io.tmpdir}.
     */
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
        for (int i = 0; i < poolSize; i++) {
            String serviceName = label + "-" + i;
            tryStartMember(pool, serviceName, cmd, projectRoot, commandTimeoutMs);
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
            List<String> launchedCmd = withUniqueJavaIoTmpDir(cmd, serviceName, projectRoot);
            ServiceProcessSupervisor supervisor =
                    new ServiceProcessSupervisor(serviceName, launchedCmd, projectRoot, commandTimeoutMs);
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
     * Isolates OpenCV native extract dirs per worker so Windows loaders do not delete each other's DLLs.
     */
    static List<String> withUniqueJavaIoTmpDir(List<String> command, String serviceName, Path projectRoot)
            throws IOException {
        if (command == null || command.isEmpty() || !looksLikeJavaLauncher(command.get(0))) {
            return command;
        }
        for (String arg : command) {
            if (arg != null && arg.startsWith("-Djava.io.tmpdir=")) {
                return command;
            }
        }
        Path base = projectRoot == null
                ? Path.of(System.getProperty("java.io.tmpdir", "."))
                : projectRoot.resolve(".tmp").resolve("svc-io");
        Path tmp = base.resolve(sanitizeServiceName(serviceName));
        Files.createDirectories(tmp);
        List<String> out = new ArrayList<>(command.size() + 1);
        out.add(command.get(0));
        out.add("-Djava.io.tmpdir=" + tmp.toAbsolutePath().normalize());
        out.addAll(command.subList(1, command.size()));
        return List.copyOf(out);
    }

    private static boolean looksLikeJavaLauncher(String first) {
        if (first == null || first.isBlank()) {
            return false;
        }
        String name = Path.of(first.trim()).getFileName().toString().toLowerCase(Locale.ROOT);
        return "java".equals(name) || "java.exe".equals(name);
    }

    private static String sanitizeServiceName(String serviceName) {
        String raw = serviceName == null ? "svc" : serviceName.trim();
        if (raw.isEmpty()) {
            return "svc";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
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
