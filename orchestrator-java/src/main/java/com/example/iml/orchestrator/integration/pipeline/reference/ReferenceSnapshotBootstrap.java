package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.pipeline.spi.PipelineRunTelemetry;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Захват эталона в SHM и рассылка заголовка в python/UI-visuals (единая точка для conveyor и обычного режима).
 */
public final class ReferenceSnapshotBootstrap {

    private final Logger log;
    private final CameraCaptureStage capture;
    private final PipelineRunTelemetry telemetry;

    public ReferenceSnapshotBootstrap(Logger log, CameraCaptureStage capture, PipelineRunTelemetry telemetry) {
        this.log = log;
        this.capture = capture;
        this.telemetry = telemetry;
    }

    /**
     * @param logReuseDebug если true и эталон не переснимался — debug про reuse (как в основном режиме без conveyor).
     */
    public ReferenceBootstrapOutcome ensure(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            String shmProductType,
            String logProductTypeForReuse,
            String detectorId,
            boolean needCapture,
            ReferenceSnapshot existingSnapshot,
            WorkerProcessSupervisor worker,
            LightTriggerClient lightClient,
            List<? extends BinaryRpcSupervisor> pythonPool,
            BinaryRpcSupervisor uiVisualsPython,
            int referenceRepeatCount,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            PipelineStagesLog pipelineStagesLog,
            Map<String, Object> telemetryExtras,
            ReferenceLogStyle logStyle,
            boolean logReuseDebug
    ) throws Exception {
        if (!needCapture) {
            if (logReuseDebug && log.isDebugEnabled() && existingSnapshot != null) {
                log.debug("worker cam={} reuse reference product_type={} frame_id={}",
                        cameraId, logProductTypeForReuse, existingSnapshot.header().get("frame_id"));
            }
            return new ReferenceBootstrapOutcome(existingSnapshot, 0L);
        }
        long tRef0 = System.nanoTime();
        lightClient.trigger(cameraId, -1, "reference");
        BinaryProtocol.Message referenceCapture = worker.command(Map.of("op", "capture"));
        if (logStyle == ReferenceLogStyle.CONVEYOR_BUCKET) {
            log.info("worker cam={} reference capture header={} (conveyor)", cameraId, referenceCapture.header());
        } else {
            log.info("worker cam={} reference capture header={}", cameraId, referenceCapture.header());
        }
        capture.saveReferenceCapture(projectRoot, saveCaptures, referenceCapture);
        ReferenceSnapshot snapshot = new ReferenceSnapshot(shmProductType, Map.copyOf(referenceCapture.header()));
        referenceByCamera.put(cameraId, snapshot);
        Map<String, Object> refHdr = BinaryInspectHeaders.setReferenceShmHeader(shmProductType, detectorId, referenceCapture.header());
        for (int r = 0; r < referenceRepeatCount; r++) {
            for (BinaryRpcSupervisor python : pythonPool) {
                python.command(refHdr);
            }
            if (uiVisualsPython != null) {
                uiVisualsPython.command(refHdr);
            }
        }
        long wallMs = YamlScalars.nanosToMs(System.nanoTime() - tRef0);
        telemetry.logReferenceSnapshot(
                pipelineStagesLog,
                cameraId,
                shmProductType,
                wallMs,
                referenceRepeatCount,
                telemetryExtras == null ? Map.of() : telemetryExtras
        );
        return new ReferenceBootstrapOutcome(snapshot, wallMs);
    }
}
