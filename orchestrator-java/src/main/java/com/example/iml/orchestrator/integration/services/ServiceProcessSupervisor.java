package com.example.iml.orchestrator.integration.services;

import com.example.iml.orchestrator.integration.binaryrpc.AbstractBinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.BinaryProcessClient;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Дочерний сервис с бинарным протоколом (Python geometry/detectors и т.п.): stdio, перезапуск при сбое.
 */
public final class ServiceProcessSupervisor extends AbstractBinaryRpcSupervisor implements BinaryRpcSupervisor {

    private static final Logger log = LogManager.getLogger(ServiceProcessSupervisor.class);

    private final String name;
    private final List<String> command;
    private final Path workingDir;

    public ServiceProcessSupervisor(String name, List<String> command, Path workingDir, int commandTimeoutMs) {
        super(commandTimeoutMs, r -> {
            Thread t = new Thread(r, "svc-call-" + name);
            t.setDaemon(true);
            return t;
        });
        this.name = name;
        this.command = command;
        this.workingDir = workingDir;
    }

    @Override
    public String supervisorLabel() {
        return name;
    }

    /** Имя процесса в конфиге (совпадает с {@link #supervisorLabel()}). */
    public String name() {
        return name;
    }

    @Override
    public void start() throws IOException {
        if (client != null && client.isAlive()) {
            return;
        }
        client = new BinaryProcessClient(command, workingDir);
        log.info("{} supervisor started", name);
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        ensureAlive();
        try {
            return commandNoRetry(header);
        } catch (IOException first) {
            log.warn("{} command failed; restart and retry: {}", name, first.getMessage());
            restart();
            return commandNoRetry(header);
        }
    }

    @Override
    protected void ensureAlive() throws IOException {
        if (client == null || !client.isAlive()) {
            log.warn("{} is not alive; restarting", name);
            restart();
        }
    }
}
