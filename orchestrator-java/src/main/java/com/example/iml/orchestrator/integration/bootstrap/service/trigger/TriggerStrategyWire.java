package com.example.iml.orchestrator.integration.bootstrap.service.trigger;

import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringSink;
import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategyFactory;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.impl.BusTriggerStrategyImpl;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Wire shared inspection trigger strategy and optional bucket line broadcaster.
 */
public final class TriggerStrategyWire {

    private final Logger log;

    public TriggerStrategyWire(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void wire(
            TriggerWiringSink sink,
            InspectionTriggerRuntime triggerRuntime,
            BucketInspectionConfig bucketInspectionConfig,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub
    ) {
        InspectionTriggerStrategy sharedTriggerStrategy;
        if (bucketInspectionConfig.enabled()) {
            if (triggerMode != IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
                long broadcastIntervalMs = triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.TIMER
                        ? devAutoTriggerStub.intervalMs()
                        : Math.max(
                                bucketInspectionConfig.lineBroadcastIntervalMs(),
                                continuousInspection.cycleDelayMs()
                        );
                BucketLineTriggerBroadcaster broadcaster = new BucketLineTriggerBroadcaster(
                        log,
                        triggerRuntime.bus(),
                        broadcastIntervalMs
                );
                broadcaster.start();
                sink.setBucketLineTriggerBroadcaster(broadcaster);
            }
            sharedTriggerStrategy = new BusTriggerStrategyImpl(triggerRuntime.bus());
        } else {
            sharedTriggerStrategy = InspectionTriggerStrategyFactory.create(
                    triggerMode,
                    triggerRuntime.bus(),
                    devAutoTriggerStub,
                    continuousInspection
            );
        }
        sink.setSharedTriggerStrategy(sharedTriggerStrategy);
    }

    public void logSaveAndTriggerInfo(
            TriggerConfigView config,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            InspectionTriggerConfig triggerCfg,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub
    ) {
        IntegrationFeatureConfig.SaveCapturesConfig saveCaptures =
                BootstrapInspectionFeatures.saveCaptures(config.integration());
        if (saveCaptures.enabled()) {
            log.info("save_captures enabled dir={} (от корня проекта)", saveCaptures.relativeDir());
        }
        if (devAutoTriggerStub.enabled()) {
            log.info("dev_auto_trigger_stub enabled interval_ms={}", devAutoTriggerStub.intervalMs());
        } else if (continuousInspection.enabled()) {
            log.info("continuous_inspection enabled cycle_delay_ms={}", continuousInspection.cycleDelayMs());
        } else if (triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
            if (triggerCfg.usesIoInputMonitor()) {
                log.info(
                        "inspection_trigger external io_input {}:{} di={}/{}/{} trigger_edge={} di3_only={} direction_latch_on_work={} direction_arm_next_di3={} require_direction={} require_work={} direction_invert={} direction_wait_ms={} direction_poll_ms={} debounce_ms={} stub_work={}",
                        triggerCfg.udp().bindHost(),
                        triggerCfg.udp().bindPort(),
                        triggerCfg.ioInput().workPort(),
                        triggerCfg.ioInput().directionPort(),
                        triggerCfg.ioInput().triggerPort(),
                        triggerCfg.ioInput().triggerEdge(),
                        triggerCfg.ioInput().di3Only(),
                        triggerCfg.ioInput().directionLatchOnWork(),
                        triggerCfg.ioInput().directionArmNextDi3(),
                        triggerCfg.ioInput().requireDirection(),
                        triggerCfg.ioInput().requireWork(),
                        triggerCfg.ioInput().directionInvert(),
                        triggerCfg.ioInput().directionWaitMs(),
                        triggerCfg.ioInput().directionPollMs(),
                        triggerCfg.ioInput().debounceMs(),
                        triggerCfg.ioInput().stubWorkActive()
                );
            } else if (triggerCfg.udp().enabled()) {
                log.info(
                        "inspection_trigger external udp {}:{} format={}",
                        triggerCfg.udp().bindHost(),
                        triggerCfg.udp().bindPort(),
                        triggerCfg.udp().format()
                );
            } else {
                log.warn("inspection_trigger external mode but udp.enabled=false");
            }
        }
    }
}
