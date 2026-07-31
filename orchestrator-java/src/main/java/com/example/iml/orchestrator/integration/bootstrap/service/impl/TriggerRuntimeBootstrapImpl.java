package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;
import com.example.iml.orchestrator.integration.bootstrap.service.api.TriggerRuntimeBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringHost;
import com.example.iml.orchestrator.integration.bootstrap.service.trigger.BucketInspectionWire;
import com.example.iml.orchestrator.integration.bootstrap.service.trigger.IntervalFlashWire;
import com.example.iml.orchestrator.integration.bootstrap.service.trigger.LineCaptureWire;
import com.example.iml.orchestrator.integration.bootstrap.service.trigger.ShutdownPrepDiWire;
import com.example.iml.orchestrator.integration.bootstrap.service.trigger.TriggerModeResolver;
import com.example.iml.orchestrator.integration.bootstrap.service.trigger.TriggerStrategyWire;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates one-shot wiring of line-sync, trigger runtime, interval flash, bucket and strategy.
 * Domain details live in {@code bootstrap.service.trigger.*} collaborators.
 */
public final class TriggerRuntimeBootstrapImpl extends AbstractBootstrapService implements TriggerRuntimeBootstrap {

    private final TriggerModeResolver modeResolver;
    private final BucketInspectionWire bucketWire;
    private final LineCaptureWire lineCaptureWire;
    private final ShutdownPrepDiWire shutdownPrepWire;
    private final IntervalFlashWire intervalFlashWire;
    private final TriggerStrategyWire strategyWire;

    public TriggerRuntimeBootstrapImpl(Logger log) {
        super(log);
        this.modeResolver = new TriggerModeResolver(log);
        this.bucketWire = new BucketInspectionWire(log);
        this.lineCaptureWire = new LineCaptureWire(log);
        this.shutdownPrepWire = new ShutdownPrepDiWire(log);
        this.intervalFlashWire = new IntervalFlashWire(log);
        this.strategyWire = new TriggerStrategyWire(log);
    }

    @Override
    public TriggerRuntimeBootstrap.TriggerWireResult wire(TriggerWiringHost ctx) {
        TriggerModeResolver.ResolvedTriggerMode mode = modeResolver.resolve(ctx.integration());
        BucketInspectionWire.BucketWireResult bucket = bucketWire.wire(ctx, ctx, ctx);

        lineCaptureWire.wire(ctx, ctx, ctx, bucket.inspectionCameraIds());

        AtomicBoolean softwareVisionReady = new AtomicBoolean(false);
        InspectionTriggerRuntime[] triggerRuntimeHolder = new InspectionTriggerRuntime[1];
        Runnable refreshVisionReady = () -> {
            // Ready держит FanOutCoordinator.onSessionState (эталон → FINS vision_ready sticky).
        };
        InspectionTriggerRuntime triggerRuntime = InspectionTriggerRuntime.start(
                log,
                ctx.integration(),
                bucket.inspectionCameraIds(),
                mode.triggerMode(),
                ctx.bootConfig().captureTriggerStaggerMs(),
                refreshVisionReady,
                triggerRuntimeHolder,
                ctx.manualLineDirection()
        );
        ctx.setTriggerRuntime(triggerRuntime);
        shutdownPrepWire.wire(ctx, mode.inspectionTriggerConfig(), triggerRuntime);

        intervalFlashWire.wire(ctx, ctx, ctx);

        if (ctx.lineCaptureCoordinator() != null) {
            LineSynchronizedCaptureCoordinator lineCaptureRef = ctx.lineCaptureCoordinator();
            triggerRuntime.bus().setLineTriggerListener((seq, at, cameraIds) ->
                    lineCaptureRef.prefireLineTrigger(seq, at.toEpochMilli(), cameraIds));
        }

        IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub =
                BootstrapInspectionFeatures.devAutoTriggerStub(ctx.integration());
        strategyWire.wire(
                ctx,
                triggerRuntime,
                bucket.bucketInspectionConfig(),
                mode.triggerMode(),
                mode.continuousInspection(),
                devAutoTriggerStub
        );
        strategyWire.logSaveAndTriggerInfo(
                ctx,
                mode.continuousInspection(),
                mode.triggerMode(),
                mode.inspectionTriggerConfig(),
                devAutoTriggerStub
        );

        return new TriggerRuntimeBootstrap.TriggerWireResult(
                mode.triggerMode(),
                bucket.inspectionCameraIds(),
                mode.continuousInspection(),
                devAutoTriggerStub,
                softwareVisionReady,
                refreshVisionReady
        );
    }
}
