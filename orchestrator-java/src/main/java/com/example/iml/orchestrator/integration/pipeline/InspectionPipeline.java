package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceBootstrapOutcome;
import com.example.iml.orchestrator.integration.pipeline.session.AsyncInspectionCycleInput;
import com.example.iml.orchestrator.integration.pipeline.session.ProductionInspectionOrchestrator;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

import java.nio.file.Path;
import java.util.Map;

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
            CameraInspectionDeps deps,
            InspectionTriggerStrategy triggerStrategy,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            boolean captureWithoutReference
    ) throws Exception {
        int cameraId = ((Number) camera.get("id")).intValue();
        String productType = ConfiguredCameras.analysisProfileForCamera(camera, cameraId);
        String detectorId = String.valueOf(camera.getOrDefault("detector", "v1"));
        boolean reloadReferenceLocal = YamlScalars.toBool(camera.get("reload_reference"), false);
        long tCameraStartNanos = System.nanoTime();

        Map<Integer, ReferenceSnapshot> referenceByCamera = deps.referenceByCamera();
        ReferenceSnapshot referenceSnapshot = referenceByCamera.get(cameraId);
        boolean needReference = referenceSnapshot == null
                || !productType.equals(referenceSnapshot.productType())
                || deps.reloadReferenceGlobal()
                || reloadReferenceLocal;
        long referenceMsFinal = 0L;
        ReferenceSnapshot activeReference = referenceSnapshot;
        boolean referenceFromClient = deps.referenceSource() == ReferenceSource.CLIENT;
        if (referenceFromClient) {
            if (needReference && !captureWithoutReference) {
                svc.log().info(
                        "worker cam={}: waiting for client.reference_bundle (integration.reference_source=client)",
                        cameraId
                );
            } else if (needReference) {
                svc.log().info(
                        "worker cam={}: capture_without_reference enabled — trigger capture without client.reference_bundle",
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
                        deps.lighting(),
                        deps.pythonPool(),
                        deps.uiVisualsPython(),
                        1,
                        referenceByCamera,
                        deps.pipelineStagesLog(),
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

        AsyncInspectionCycleInput in = AsyncInspectionCycleInput.fromDeps(
                projectRoot,
                saveCaptures,
                cameraId,
                productType,
                detectorId,
                activeReference,
                referenceMsFinal,
                tCameraStartNanos,
                worker,
                deps
        );

        ProductionInspectionOrchestrator.run(
                svc,
                in,
                triggerStrategy,
                triggerMode,
                deps.referenceSource(),
                referenceByCamera,
                deps.inspectionGate(),
                deps.inspectionCycleTimeoutMs(),
                captureWithoutReference
        );
    }
}
