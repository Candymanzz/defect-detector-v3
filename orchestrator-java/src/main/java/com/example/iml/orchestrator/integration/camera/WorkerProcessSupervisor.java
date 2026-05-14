package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.integration.binaryrpc.AbstractBinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Процесс camera-worker на камеру: перезапуск при обрыве, таймауты команд.
 */
public final class WorkerProcessSupervisor extends AbstractBinaryRpcSupervisor implements BinaryRpcSupervisor {

    private static final Logger log = LogManager.getLogger(WorkerProcessSupervisor.class);

    private final int cameraId;
    private final List<String> command;
    private final Path workingDir;
    private final WorkerIpcMode ipcMode;
    private final String namedPipePath;
    private final int namedPipeConnectTimeoutMs;

    public WorkerProcessSupervisor(
            int cameraId,
            List<String> command,
            Path workingDir,
            WorkerIpcMode ipcMode,
            String namedPipePath,
            int namedPipeConnectTimeoutMs,
            int commandTimeoutMs
    ) {
        super(commandTimeoutMs, r -> {
            Thread t = new Thread(r, "worker-call-" + cameraId);
            t.setDaemon(true);
            return t;
        });
        this.cameraId = cameraId;
        this.command = command;
        this.workingDir = workingDir;
        this.ipcMode = ipcMode;
        this.namedPipePath = namedPipePath;
        this.namedPipeConnectTimeoutMs = namedPipeConnectTimeoutMs;
    }

    @Override
    public String supervisorLabel() {
        return "worker[cameraId=" + cameraId + "]";
    }

    @Override
    public void start() throws IOException {
        if (client != null && client.isAlive()) {
            return;
        }
        this.client = createClient();
        log.info("worker supervisor started camera={} ipc_mode={}", cameraId, ipcMode);
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            ensureAlive();
            try {
                return commandNoRetry(header);
            } catch (IOException error) {
                lastError = error;
                if (attempt == 3) {
                    break;
                }
                log.warn("worker camera={} command failed on attempt {}/3; restarting: {}", cameraId, attempt, error.getMessage());
                restart();
            }
        }
        throw lastError == null ? new IOException("worker command failed") : lastError;
    }

    @Override
    protected void ensureAlive() throws IOException {
        if (client == null || !client.isAlive()) {
            log.warn("worker camera={} is not alive; restarting", cameraId);
            restart();
        }
    }

    private BinaryClient createClient() throws IOException {
        return switch (ipcMode) {
            case STDIO -> new BinaryProcessClient(command, workingDir);
            case NAMED_PIPE -> new NamedPipeBinaryClient(command, workingDir, namedPipePath, namedPipeConnectTimeoutMs);
        };
    }
}
