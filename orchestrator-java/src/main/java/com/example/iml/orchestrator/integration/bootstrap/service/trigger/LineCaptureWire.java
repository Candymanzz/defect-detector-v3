package com.example.iml.orchestrator.integration.bootstrap.service.trigger;

import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfig;
import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfigMapper;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringSink;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Wire multi-camera line-synchronized capture coordinator.
 */
public final class LineCaptureWire {

    private final Logger log;

    public LineCaptureWire(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void wire(
            TriggerConfigView config,
            TriggerCollaboratorView collaborators,
            TriggerWiringSink sink,
            List<Integer> inspectionCameraIds
    ) {
        SimultaneousLineCaptureConfig lineCaptureCfg =
                SimultaneousLineCaptureConfigMapper.fromYaml(config.integration(), config.root());
        if (lineCaptureCfg.enabled() && inspectionCameraIds.size() > 1) {
            LineSynchronizedCaptureCoordinator lineCaptureCoordinator = new LineSynchronizedCaptureCoordinator(
                    inspectionCameraIds,
                    lineCaptureCfg.barrierWaitMs(),
                    lineCaptureCfg.postTriggerSettleMs(),
                    lineCaptureCfg.interWaitFrameMs(),
                    lineCaptureCfg.parallelWaitFrame(),
                    lineCaptureCfg.immediatePrefire(),
                    lineCaptureCfg.hardwareLineTrigger(),
                    lineCaptureCfg.transferWaitWaves(),
                    lineCaptureCfg.transferWaveGapMs()
            );
            lineCaptureCoordinator.bindWorkers(collaborators.workersByCamera());
            sink.setLineCaptureCoordinator(lineCaptureCoordinator);
            collaborators.captureCoordinator().setLineCaptureCoordinator(lineCaptureCoordinator);
            SimultaneousLineCaptureConfigMapper.logTopology(lineCaptureCfg, log, config.root());
            if (lineCaptureCfg.hardwareLineTrigger()) {
                log.info(
                        "hardware_line_trigger: экспозиция по DI3→Line0, Java только wait_frame (без trigger_only/settle/barrier)"
                );
                log.warn(
                        "hardware_line_trigger требует физическую разводку DI3→Line0 всех камер; "
                                + "без неё wait_frame будет timeout (0x80000007)"
                );
            }
        } else if (config.bootConfig().captureTriggerStaggerMs() > 0) {
            log.info(
                    "inspection trigger stagger enabled delay_ms={} cameras={}",
                    config.bootConfig().captureTriggerStaggerMs(),
                    inspectionCameraIds.size()
            );
        } else {
            log.info(
                    "line synchronized capture disabled (enabled={} cameras={})",
                    lineCaptureCfg.enabled(),
                    inspectionCameraIds.size()
            );
        }
    }
}
