package com.example.iml.orchestrator.integration.bootstrap.context.state;

import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** Stage executor pools, SHM janitor, pipeline timing log. */
public final class CameraStageRuntimeState {

    private ScheduledExecutorService shmJanitorScheduler;
    private PipelineStagesLog pipelineStagesLog;
    private ExecutorService cameraExecutor;
    private ExecutorService captureStageExecutor;
    private ExecutorService pythonStageExecutor;
    private ExecutorService geometryStageExecutor;
    private ExecutorService decisionStageExecutor;

    public ScheduledExecutorService shmJanitorScheduler() {
        return shmJanitorScheduler;
    }

    public void setShmJanitorScheduler(ScheduledExecutorService shmJanitorScheduler) {
        this.shmJanitorScheduler = shmJanitorScheduler;
    }

    public PipelineStagesLog pipelineStagesLog() {
        return pipelineStagesLog;
    }

    public void setPipelineStagesLog(PipelineStagesLog pipelineStagesLog) {
        this.pipelineStagesLog = pipelineStagesLog;
    }

    public ExecutorService cameraExecutor() {
        return cameraExecutor;
    }

    public void setCameraExecutor(ExecutorService cameraExecutor) {
        this.cameraExecutor = cameraExecutor;
    }

    public ExecutorService captureStageExecutor() {
        return captureStageExecutor;
    }

    public void setCaptureStageExecutor(ExecutorService captureStageExecutor) {
        this.captureStageExecutor = captureStageExecutor;
    }

    public ExecutorService pythonStageExecutor() {
        return pythonStageExecutor;
    }

    public void setPythonStageExecutor(ExecutorService pythonStageExecutor) {
        this.pythonStageExecutor = pythonStageExecutor;
    }

    public ExecutorService geometryStageExecutor() {
        return geometryStageExecutor;
    }

    public void setGeometryStageExecutor(ExecutorService geometryStageExecutor) {
        this.geometryStageExecutor = geometryStageExecutor;
    }

    public ExecutorService decisionStageExecutor() {
        return decisionStageExecutor;
    }

    public void setDecisionStageExecutor(ExecutorService decisionStageExecutor) {
        this.decisionStageExecutor = decisionStageExecutor;
    }
}
