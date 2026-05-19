package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
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
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            List<? extends BinaryRpcSupervisor> pythonPool,
            List<? extends BinaryRpcSupervisor> geometryPool,
            ExternalServiceProcess lightServerProcess,
            ExternalServiceProcess lightServerV2Process,
            ExternalServiceProcess analisSurfaceProcess,
            LightTriggerClient lightTriggerClient,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            FanOutCoordinator fanOut,
            ClientWebSocketServer clientWebSocketServer,
            UiHttpServer uiServer,
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
        if (r.lightTriggerClient != null) {
            try {
                r.log.info("turning off all lights before shutdown");
                r.lightTriggerClient.forceAllOff();
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                r.log.warn("light forceAllOff: {}", e.getMessage());
            }
            r.lightTriggerClient.shutdown();
        }
        if (r.lightServerProcess != null) {
            r.lightServerProcess.close();
        }
        if (r.lightServerV2Process != null) {
            r.lightServerV2Process.close();
        }
        if (r.analisSurfaceProcess != null) {
            r.analisSurfaceProcess.close();
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
    }
}
