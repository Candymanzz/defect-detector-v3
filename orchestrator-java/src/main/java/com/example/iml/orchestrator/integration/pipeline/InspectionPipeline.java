package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceBootstrapOutcome;
import com.example.iml.orchestrator.integration.pipeline.session.AsyncInspectionCycleInput;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.session.ProductionInspectionOrchestrator;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Точка входа потока камеры: выбор режима и делегирование оркестраторам (без реализации сценариев внутри).
 */
public final class InspectionPipeline {

    private final InspectionPipelineServices svc;

    public InspectionPipeline(InspectionPipelineServices services) {
        this.svc = services;
    }

    public void processCamera(
            Path projectRoot,
            Map<String, Object> camera,
            WorkerProcessSupervisor worker,
            List<? extends BinaryRpcSupervisor> pythonPool,
            List<? extends BinaryRpcSupervisor> geometryPool,
            LightTriggerClient lightClient,
            Map<String, Object> pythonCfg,
            Map<String, Object> geometryCfg,
            FanOutCoordinator fanOut,
            Semaphore geometrySlots,
            Semaphore pythonSlots,
            AtomicInteger geometryRoundRobin,
            AtomicInteger pythonRoundRobin,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            ReferenceSource referenceSource,
            boolean reloadReferenceGlobal,
            ExecutorService captureStageExecutor,
            ExecutorService pythonStageExecutor,
            ExecutorService geometryStageExecutor,
            ExecutorService decisionStageExecutor,
            Map<String, Object> uiCfg,
            UiHttpServer uiServer,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            InspectionTriggerStrategy triggerStrategy,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int flashLeadMs,
            PipelineStagesLog pipelineStagesLog,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            BucketInspectionAggregator bucketAggregator
    ) throws Exception {
        int cameraId = ((Number) camera.get("id")).intValue();
        String productType = ConfiguredCameras.analysisProfileForCamera(camera, cameraId);
        String detectorId = String.valueOf(camera.getOrDefault("detector", "v1"));
        boolean reloadReferenceLocal = YamlScalars.toBool(camera.get("reload_reference"), false);
        long tCameraStartNanos = System.nanoTime();

        AsyncInspectionCycleInput shared = AsyncInspectionCycleInput.of(
                projectRoot,
                saveCaptures,
                cameraId,
                productType,
                detectorId,
                referenceByCamera.get(cameraId),
                0L,
                tCameraStartNanos,
                worker,
                lightClient,
                pythonPool,
                geometryPool,
                pythonCfg,
                geometryCfg,
                fanOut,
                geometrySlots,
                pythonSlots,
                geometryRoundRobin,
                pythonRoundRobin,
                captureStageExecutor,
                pythonStageExecutor,
                geometryStageExecutor,
                decisionStageExecutor,
                uiCfg,
                uiServer,
                uiVisualsPython,
                uiArtifactsExecutor,
                flashLeadMs,
                pipelineStagesLog,
                0L,
                0L,
                bucketAggregator
        );

        ReferenceSnapshot referenceSnapshot = referenceByCamera.get(cameraId);
        boolean needReference = referenceSnapshot == null
                || !productType.equals(referenceSnapshot.productType())
                || reloadReferenceGlobal
                || reloadReferenceLocal;
        long referenceMsFinal = 0L;
        ReferenceSnapshot activeReference = referenceSnapshot;
        boolean referenceFromClient = referenceSource == ReferenceSource.CLIENT;
        if (referenceFromClient) {
            if (needReference) {
                svc.log().info(
                        "worker cam={}: waiting for client.reference_bundle (integration.reference_source=client)",
                        cameraId
                );
            }
        } else {
            try {
                ReferenceBootstrapOutcome refMain = svc.referenceBootstrap().ensure(
                        projectRoot,
                        saveCaptures,
                        cameraId,
                        productType,
                        productType,
                        detectorId,
                        needReference,
                        referenceSnapshot,
                        worker,
                        lightClient,
                        pythonPool,
                        uiVisualsPython,
                        1,
                        referenceByCamera,
                        pipelineStagesLog,
                        null,
                        true
                );
                activeReference = refMain.snapshot();
                referenceMsFinal = refMain.referenceWallMs();
            } catch (Exception e) {
                svc.log().error(
                        "worker cam={} reference bootstrap failed (inspection loop will continue): {}",
                        cameraId,
                        e.getMessage(),
                        e
                );
                activeReference = referenceByCamera.get(cameraId);
            }
        }

        AsyncInspectionCycleInput in = shared.withPerCycleIdentity(productType, activeReference, referenceMsFinal);

        ProductionInspectionOrchestrator.run(
                svc,
                in,
                triggerStrategy,
                triggerMode,
                referenceSource,
                referenceByCamera,
                inspectionGate,
                inspectionCycleTimeoutMs
        );
    }
}
