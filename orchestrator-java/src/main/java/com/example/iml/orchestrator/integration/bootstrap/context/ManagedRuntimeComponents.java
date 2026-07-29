package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.CloseableIntegrationComponent;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashController;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Снимок closeable-компонентов camera-runtime для lifecycle composite.
 */
public record ManagedRuntimeComponents(
        BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster,
        BucketInspectionAggregator bucketInspectionAggregator,
        LivePreviewPublisher livePreview,
        CameraStreamService cameraStreamService,
        InspectionTriggerRuntime triggerRuntime,
        CriticalServiceWatchdog criticalServiceWatchdog
) {

    public static ManagedRuntimeComponents from(CameraRuntimeContext runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new ManagedRuntimeComponents(
                runtime.triggers().bucketLineTriggerBroadcaster(),
                runtime.triggers().bucketInspectionAggregator(),
                runtime.preview().livePreview(),
                runtime.workers().cameraStreamService(),
                runtime.triggers().triggerRuntime(),
                runtime.health().criticalServiceWatchdog()
        );
    }

    public List<IntegrationComponent> toLifecycleComponents() {
        return Stream.of(
                        CloseableIntegrationComponent.ofNullable(bucketLineTriggerBroadcaster),
                        CloseableIntegrationComponent.ofNullable(bucketInspectionAggregator),
                        CloseableIntegrationComponent.ofNullable(livePreview),
                        CloseableIntegrationComponent.ofNullable(cameraStreamService),
                        CloseableIntegrationComponent.ofNullable(triggerRuntime),
                        CloseableIntegrationComponent.ofNullable(criticalServiceWatchdog)
                )
                .filter(Objects::nonNull)
                .toList();
    }
}
