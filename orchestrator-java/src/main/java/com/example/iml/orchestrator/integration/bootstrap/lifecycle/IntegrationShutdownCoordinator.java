package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Закрытие ресурсов интеграции в обратном порядке зависимостей (вынесено из god-metода {@code start}).
 */
public final class IntegrationShutdownCoordinator {

    private IntegrationShutdownCoordinator() {
    }

    public record ShutdownResources(
            PipelineStagesLog pipelineStagesLog,
            ExecutorService cameraExecutor,
            ExecutorService captureStageExecutor,
            ExecutorService pythonStageExecutor,
            ExecutorService geometryStageExecutor,
            ExecutorService decisionStageExecutor,
            java.util.concurrent.ScheduledExecutorService shmJanitorScheduler,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            List<? extends BinaryRpcSupervisor> pythonPool,
            List<? extends BinaryRpcSupervisor> geometryPool,
            List<? extends BinaryRpcSupervisor> positioningPool,
            ExternalServiceProcess lightServerProcess,
            ExternalServiceProcess ioInputMonitorProcess,
            ExternalServiceProcess frontendProcess,
            List<ExternalServiceProcess> analisSurfaceProcesses,
            LightTriggerClient lightTriggerClient,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            FanOutCoordinator fanOut,
            ClientWebSocketServer clientWebSocketServer,
            UiHttpServer uiServer,
            FrameArchiveService frameArchiveService,
            ServicePoolLifecycle servicePools,
            Logger log
    ) {
    }

    public static void shutdownAll(ShutdownResources r) {
        if (r.pipelineStagesLog != null) {
            try {
                r.pipelineStagesLog.close();
            } catch (Exception ignored) {
            }
        }
        if (r.shmJanitorScheduler != null) {
            r.shmJanitorScheduler.shutdownNow();
        }
        if (r.cameraExecutor != null) {
            r.cameraExecutor.shutdownNow();
        }
        r.servicePools.shutdownExecutor(r.captureStageExecutor);
        r.servicePools.shutdownExecutor(r.pythonStageExecutor);
        r.servicePools.shutdownExecutor(r.geometryStageExecutor);
        r.servicePools.shutdownExecutor(r.decisionStageExecutor);
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : r.workersByCamera.entrySet()) {
            try {
                r.log.info("worker supervisor camera={} restarts={}", entry.getKey(), entry.getValue().restartCount());
                entry.getValue().close();
            } catch (Exception ignored) {
            }
        }
        for (BinaryRpcSupervisor python : r.pythonPool()) {
            if (python != null) {
                r.log.info("{} supervisor restarts={}", python.supervisorLabel(), python.restartCount());
                python.close();
            }
        }
        for (BinaryRpcSupervisor geometry : r.geometryPool()) {
            if (geometry != null) {
                r.log.info("{} supervisor restarts={}", geometry.supervisorLabel(), geometry.restartCount());
                geometry.close();
            }
        }
        if (r.positioningPool() != null) {
            for (BinaryRpcSupervisor positioning : r.positioningPool()) {
                if (positioning != null) {
                    try {
                        r.log.info("{} supervisor restarts={}", positioning.supervisorLabel(), positioning.restartCount());
                        positioning.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // Вспышки Off + kill LightServer (идемпотентно с JVM shutdown hook).
        com.example.iml.orchestrator.integration.lighting.LightsShutdown.run("shutdown-coordinator");
        // Если hook уже отработал — процесса в refs нет; на всякий случай ещё раз close по полю.
        if (r.lightServerProcess != null) {
            try {
                r.lightServerProcess.close();
            } catch (Exception ignored) {
            }
        }
        if (r.ioInputMonitorProcess != null) {
            r.ioInputMonitorProcess.close();
        }
        if (r.frontendProcess != null) {
            r.frontendProcess.close();
        }
        if (r.analisSurfaceProcesses != null) {
            for (ExternalServiceProcess process : r.analisSurfaceProcesses) {
                if (process != null) {
                    process.close();
                }
            }
        }
        if (r.uiVisualsPython != null) {
            r.log.info("{} supervisor restarts={}", r.uiVisualsPython.supervisorLabel(), r.uiVisualsPython.restartCount());
            r.uiVisualsPython.close();
        }
        r.servicePools.shutdownExecutor(r.uiArtifactsExecutor);
        if (r.fanOut != null) {
            r.log.info("fanout metrics: {}", r.fanOut.metricsSummary());
            r.fanOut.close();
        }
        if (r.clientWebSocketServer() != null) {
            try {
                r.clientWebSocketServer().close();
            } catch (Exception ignored) {
            }
        }
        if (r.uiServer != null) {
            r.uiServer.close();
        }
        if (r.frameArchiveService != null) {
            try {
                r.frameArchiveService.close();
            } catch (Exception ignored) {
            }
        }
        ImlShmJanitor.purgeOrchestratorBuffers(r.log);
    }
}
