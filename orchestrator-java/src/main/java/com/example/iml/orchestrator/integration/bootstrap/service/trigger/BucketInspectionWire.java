package com.example.iml.orchestrator.integration.bootstrap.service.trigger;

import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringSink;
import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import com.example.iml.orchestrator.integration.pipeline.bucket.JointSeamPolicy;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Wire bucket inspection aggregator and inspection camera gate.
 */
public final class BucketInspectionWire {

    private final Logger log;

    public BucketInspectionWire(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public record BucketWireResult(
            BucketInspectionConfig bucketInspectionConfig,
            List<Integer> inspectionCameraIds
    ) {
    }

    public BucketWireResult wire(
            TriggerConfigView config,
            TriggerCollaboratorView collaborators,
            TriggerWiringSink sink
    ) {
        BucketInspectionConfig bucketInspectionConfig =
                BootstrapInspectionFeatures.bucketInspection(
                        config.integration(), collaborators.workersByCamera().keySet());
        List<Integer> inspectionCameraIds = bucketInspectionConfig.enabled()
                ? bucketInspectionConfig.allCameraIds()
                : collaborators.workersByCamera().keySet().stream().sorted().toList();
        if (bucketInspectionConfig.enabled()) {
            BucketInspectionAggregator bucketInspectionAggregator = new BucketInspectionAggregator(
                    log,
                    bucketInspectionConfig,
                    JointSeamPolicy.fromGeometryYaml(config.geometryCfg())
            );
            sink.setBucketInspectionAggregator(bucketInspectionAggregator);
            collaborators.inspectionGate().setInspectionEnabledOnlyFor(inspectionCameraIds);
            log.info(
                    "inspection bucket enabled groups={} cameras={} timeout_ms={} line_broadcast_interval_ms={}",
                    bucketInspectionConfig.groups(),
                    bucketInspectionConfig.allCameraIds(),
                    bucketInspectionConfig.timeoutMs(),
                    bucketInspectionConfig.lineBroadcastIntervalMs()
            );
        }
        return new BucketWireResult(bucketInspectionConfig, inspectionCameraIds);
    }
}
