package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

/** Sink for objects created during trigger wiring. */
public interface TriggerWiringSink {

    LineSynchronizedCaptureCoordinator lineCaptureCoordinator();

    void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator coordinator);

    InspectionTriggerRuntime triggerRuntime();

    void setTriggerRuntime(InspectionTriggerRuntime triggerRuntime);

    void setBucketInspectionAggregator(BucketInspectionAggregator aggregator);

    void setBucketLineTriggerBroadcaster(BucketLineTriggerBroadcaster broadcaster);

    void setSharedTriggerStrategy(InspectionTriggerStrategy strategy);
}
