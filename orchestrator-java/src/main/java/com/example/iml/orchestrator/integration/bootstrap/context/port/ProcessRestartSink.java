package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.util.List;

/** Mutable process slots for critical-service restart. */
public interface ProcessRestartSink {

    ExternalServiceProcess ioInputMonitorProcess();

    void setIoInputMonitorProcess(ExternalServiceProcess process);

    ExternalServiceProcess lightServerProcess();

    void setLightServerProcess(ExternalServiceProcess process);

    List<ExternalServiceProcess> analisSurfaceProcesses();

    void setAnalisSurfaceProcesses(List<ExternalServiceProcess> processes);
}
