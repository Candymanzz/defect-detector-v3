package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceBootstrapOutcome;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceLogStyle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Режим conveyor_benchmark: ведра product_type, эталон на ведро, циклы инспекции.
 */
public final class ConveyorBenchmarkOrchestrator {

    private ConveyorBenchmarkOrchestrator() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput shared,
            IntegrationFeatureConfig.ConveyorBenchmarkConfig conveyorBenchmark,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            boolean reloadReferenceGlobal,
            boolean reloadReferenceLocal,
            IntegrationFeatureConfig.SingleFrameBenchmarkConfig singleFrameBenchmark,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection
    ) throws Exception {
        int cameraId = shared.cameraId();
        if (singleFrameBenchmark.enabled()) {
            svc.log().warn("integration: conveyor_benchmark takes precedence over single_frame_benchmark (cam={})", cameraId);
        }
        if (continuousInspection.enabled()) {
            svc.log().warn("integration: conveyor_benchmark takes precedence over continuous_inspection (cam={})", cameraId);
        }
        long tConveyor0 = System.nanoTime();
        for (int b = 0; b < conveyorBenchmark.buckets(); b++) {
            String bucketProductType = conveyorBenchmark.productTypePrefix() + String.format("%04d", b);
            svc.log().info("conveyor_benchmark bucket {}/{} product_type={}", b + 1, conveyorBenchmark.buckets(), bucketProductType);
            ReferenceSnapshot referenceSnapshot = referenceByCamera.get(cameraId);
            boolean needReference = referenceSnapshot == null
                    || !bucketProductType.equals(referenceSnapshot.productType())
                    || reloadReferenceGlobal
                    || reloadReferenceLocal;
            ReferenceBootstrapOutcome refBucket = svc.referenceBootstrap().ensure(
                    shared.projectRoot(),
                    shared.saveCaptures(),
                    cameraId,
                    bucketProductType,
                    bucketProductType,
                    shared.detectorId(),
                    needReference,
                    referenceSnapshot,
                    shared.worker(),
                    shared.lightClient(),
                    shared.pythonPool(),
                    conveyorBenchmark.referenceRepeats(),
                    referenceByCamera,
                    shared.pipelineStagesLog(),
                    Map.of("conveyor_bucket_index", b, "conveyor_buckets_total", conveyorBenchmark.buckets()),
                    ReferenceLogStyle.CONVEYOR_BUCKET,
                    false
            );
            referenceSnapshot = refBucket.snapshot();
            long bucketReferenceMs = refBucket.referenceWallMs();
            AsyncInspectionCycleInput cycle = shared.withPerCycleIdentity(bucketProductType, referenceSnapshot, bucketReferenceMs);
            for (int p = 0; p < conveyorBenchmark.photosPerBucket(); p++) {
                Map<String, Object> timingExtras = new LinkedHashMap<>();
                timingExtras.put("conveyor_bucket_index", b);
                timingExtras.put("conveyor_buckets_total", conveyorBenchmark.buckets());
                timingExtras.put("conveyor_photo_index", p);
                AsyncInspectionCycleRunner.run(svc, cycle, timingExtras);
                if (conveyorBenchmark.cycleDelayMs() > 0 && p + 1 < conveyorBenchmark.photosPerBucket()) {
                    try {
                        Thread.sleep(conveyorBenchmark.cycleDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        svc.log().info("conveyor_benchmark interrupted at bucket={} photo={}", b, p);
                        svc.log().info("conveyor_benchmark_summary cam={} buckets_completed={}/{} wall_ms={}",
                                cameraId, b, conveyorBenchmark.buckets(), YamlScalars.nanosToMs(System.nanoTime() - tConveyor0));
                        return;
                    }
                }
            }
        }
        svc.log().info("conveyor_benchmark_summary cam={} buckets={} photos_per_bucket={} wall_ms={}",
                cameraId,
                conveyorBenchmark.buckets(),
                conveyorBenchmark.photosPerBucket(),
                YamlScalars.nanosToMs(System.nanoTime() - tConveyor0));
    }
}
