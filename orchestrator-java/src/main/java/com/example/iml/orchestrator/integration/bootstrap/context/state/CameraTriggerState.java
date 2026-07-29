package com.example.iml.orchestrator.integration.bootstrap.context.state;

import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashController;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

/** Triggers, line-sync capture, bucket aggregation, interval flash. */
public final class CameraTriggerState {

    private LineSynchronizedCaptureCoordinator lineCaptureCoordinator;
    private InspectionTriggerRuntime triggerRuntime;
    private IntervalFlashController intervalFlashController;
    private BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster;
    private BucketInspectionAggregator bucketInspectionAggregator;
    private InspectionTriggerStrategy sharedTriggerStrategy;

    public LineSynchronizedCaptureCoordinator lineCaptureCoordinator() {
        return lineCaptureCoordinator;
    }

    public void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator lineCaptureCoordinator) {
        this.lineCaptureCoordinator = lineCaptureCoordinator;
    }

    public InspectionTriggerRuntime triggerRuntime() {
        return triggerRuntime;
    }

    public void setTriggerRuntime(InspectionTriggerRuntime triggerRuntime) {
        this.triggerRuntime = triggerRuntime;
    }

    public IntervalFlashController intervalFlashController() {
        return intervalFlashController;
    }

    public void setIntervalFlashController(IntervalFlashController intervalFlashController) {
        this.intervalFlashController = intervalFlashController;
    }

    public BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster() {
        return bucketLineTriggerBroadcaster;
    }

    public void setBucketLineTriggerBroadcaster(BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster) {
        this.bucketLineTriggerBroadcaster = bucketLineTriggerBroadcaster;
    }

    public BucketInspectionAggregator bucketInspectionAggregator() {
        return bucketInspectionAggregator;
    }

    public void setBucketInspectionAggregator(BucketInspectionAggregator bucketInspectionAggregator) {
        this.bucketInspectionAggregator = bucketInspectionAggregator;
    }

    public InspectionTriggerStrategy sharedTriggerStrategy() {
        return sharedTriggerStrategy;
    }

    public void setSharedTriggerStrategy(InspectionTriggerStrategy sharedTriggerStrategy) {
        this.sharedTriggerStrategy = sharedTriggerStrategy;
    }
}
