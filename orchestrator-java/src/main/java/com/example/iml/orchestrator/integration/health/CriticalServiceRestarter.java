package com.example.iml.orchestrator.integration.health;

import com.example.iml.orchestrator.integration.bootstrap.context.port.ProcessRestartHost;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Restart helpers for {@link CriticalServiceWatchdog}. */
final class CriticalServiceRestarter {

    private final ProcessRestartHost ctx;
    private final IntegrationExternalProcessLauncher externalLauncher;
    private final LightServerLauncher lightLauncher;
    private final AnalisSurfaceLauncher analisLauncher;

    CriticalServiceRestarter(
            ProcessRestartHost ctx,
            IntegrationExternalProcessLauncher externalLauncher,
            LightServerLauncher lightLauncher,
            AnalisSurfaceLauncher analisLauncher
    ) {
        this.ctx = ctx;
        this.externalLauncher = externalLauncher;
        this.lightLauncher = lightLauncher;
        this.analisLauncher = analisLauncher;
    }

    boolean restartIoInputMonitor() {
        ExternalServiceProcess old = ctx.ioInputMonitorProcess();
        if (old != null) {
            old.close();
        }
        ExternalServiceProcess next = externalLauncher.startIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                "io_input_monitor_autostart",
                "io_input_monitor_command_windows",
                "io_input_monitor_command_linux",
                "io-input-monitor",
                "."
        );
        ctx.setIoInputMonitorProcess(next);
        return next != null && next.isAlive();
    }

    boolean restartLightServer() {
        ExternalServiceProcess old = ctx.lightServerProcess();
        if (old != null) {
            old.close();
        }
        LightsShutdown.clearProcessRefOnly();
        ExternalServiceProcess next = lightLauncher.startIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                ctx.bootConfig().lightStartupDelayMs()
        );
        ctx.setLightServerProcess(next);
        if (next != null) {
            LightsShutdown.replaceProcess(next);
        }
        return next != null && next.isAlive();
    }

    boolean restartAnalisSurfacePool() {
        List<ExternalServiceProcess> old = ctx.analisSurfaceProcesses();
        if (old != null) {
            for (ExternalServiceProcess process : old) {
                if (process != null) {
                    process.close();
                }
            }
        }
        Map<String, Object> pythonCfg = ctx.pythonCfg();
        String baseUrl = pythonCfg == null
                ? null
                : String.valueOf(pythonCfg.getOrDefault("base_url", "http://127.0.0.1:8000"));
        int poolSize = Math.max(1, ctx.pythonPool() == null ? 1 : ctx.pythonPool().size());
        AnalisSurfaceLauncher.PoolStartResult result = analisLauncher.startPoolIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                baseUrl,
                poolSize,
                ctx.bootConfig().workerStartupStaggerMs()
        );
        ctx.setAnalisSurfaceProcesses(result.processes());
        return result.processes() != null
                && !result.processes().isEmpty()
                && result.processes().stream().allMatch(p -> p != null && p.isAlive());
    }

    BooleanSupplier ioInputRestart() {
        return this::restartIoInputMonitor;
    }

    BooleanSupplier lightServerRestart() {
        return this::restartLightServer;
    }

    BooleanSupplier analisRestart() {
        return this::restartAnalisSurfacePool;
    }
}
