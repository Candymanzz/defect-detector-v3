package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Порт SHM janitor / timing log.
 */
public interface RuntimeMaintenanceHost {

    Map<String, Object> integration();

    Path projectRoot();

    void setShmJanitorScheduler(ScheduledExecutorService scheduler);

    void setPipelineStagesLog(PipelineStagesLog pipelineStagesLog);
}
