package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.RuntimeMaintenanceBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.RuntimeMaintenanceHost;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Только фоновое обслуживание runtime: SHM TTL janitor + timing log.
 */
public final class RuntimeMaintenanceBootstrapImpl extends AbstractBootstrapService
        implements RuntimeMaintenanceBootstrap {

    public RuntimeMaintenanceBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void start(RuntimeMaintenanceHost session) {
        startShmJanitor(session);
        startTimingStagesLog(session);
    }

    private void startShmJanitor(RuntimeMaintenanceHost session) {
        var shmJanitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iml-shm-janitor");
            t.setDaemon(true);
            return t;
        });
        session.setShmJanitorScheduler(shmJanitorScheduler);
        shmJanitorScheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        ImlShmJanitor.purgeEphemeralOlderThan(ImlShmJanitor.DEFAULT_EPHEMERAL_TTL, log);
                    } catch (Exception e) {
                        log.warn("iml_shm ttl janitor failed: {}", e.getMessage());
                    }
                },
                15L,
                15L,
                TimeUnit.SECONDS
        );
        log.info(
                "iml_shm ttl janitor started interval_s=15 max_age_s={}",
                ImlShmJanitor.DEFAULT_EPHEMERAL_TTL.toSeconds()
        );
    }

    private void startTimingStagesLog(RuntimeMaintenanceHost session) {
        IntegrationFeatureConfig.TimingStagesLogConfig timingStagesLogCfg =
                IntegrationFeatureConfig.parseTimingStagesLog(session.integration());
        if (!timingStagesLogCfg.enabled()) {
            return;
        }
        try {
            Path timingPath = session.projectRoot().resolve(timingStagesLogCfg.relativePath());
            session.setPipelineStagesLog(new PipelineStagesLog(timingPath));
            log.info("timing_stages_log enabled jsonl={} (рядом .txt с тем же базовым именем)", timingPath);
        } catch (Exception e) {
            log.warn("timing_stages_log init failed: {}", e.getMessage());
        }
    }
}
