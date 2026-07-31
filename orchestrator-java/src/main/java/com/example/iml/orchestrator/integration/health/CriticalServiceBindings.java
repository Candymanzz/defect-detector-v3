package com.example.iml.orchestrator.integration.health;

import com.example.iml.orchestrator.integration.bootstrap.context.port.ProcessRestartHost;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Binding of external processes and binary-RPC supervisors to the health gate. */
final class CriticalServiceBindings {

    private final ProcessRestartHost ctx;
    private final CriticalServiceRestarter restarter;
    private final List<WatchedExternal> watched = new ArrayList<>();
    private final BiConsumer<String, Boolean> applySupervisorHealth;
    private final BiConsumer<String, BooleanSupplier> handleDeath;

    CriticalServiceBindings(
            ProcessRestartHost ctx,
            CriticalServiceRestarter restarter,
            BiConsumer<String, Boolean> applySupervisorHealth,
            BiConsumer<String, BooleanSupplier> handleDeath
    ) {
        this.ctx = ctx;
        this.restarter = restarter;
        this.applySupervisorHealth = applySupervisorHealth;
        this.handleDeath = handleDeath;
    }

    List<WatchedExternal> watched() {
        return watched;
    }

    void bindExternals() {
        if (ctx.ioInputMonitorProcess() != null) {
            watchExternal("io_input_monitor", ctx::ioInputMonitorProcess, restarter.ioInputRestart());
        }
        if (ctx.lightServerProcess() != null) {
            watchExternal("light_server", ctx::lightServerProcess, restarter.lightServerRestart());
        }
        if (ctx.analisSurfaceProcesses() != null && !ctx.analisSurfaceProcesses().isEmpty()) {
            watchExternal(
                    "analis_surface",
                    () -> {
                        List<ExternalServiceProcess> list = ctx.analisSurfaceProcesses();
                        if (list == null || list.isEmpty()) {
                            return null;
                        }
                        for (ExternalServiceProcess process : list) {
                            if (process != null && !process.isAlive() && !process.isClosing()) {
                                return process;
                            }
                        }
                        return list.get(0);
                    },
                    restarter.analisRestart()
            );
            for (ExternalServiceProcess process : ctx.analisSurfaceProcesses()) {
                if (process != null) {
                    attachExit(process, "analis_surface", restarter.analisRestart());
                }
            }
        }
    }

    void bindSupervisors() {
        if (ctx.workersByCamera() != null) {
            ctx.workersByCamera().forEach((cameraId, worker) -> {
                if (worker == null) {
                    return;
                }
                String key = "camera_worker_" + cameraId;
                worker.setHealthListener(ok -> applySupervisorHealth.accept(key, ok));
            });
        }
        bindPool(ctx.geometryPool(), "geometry");
        bindPool(ctx.positioningPool(), "positioning");
    }

    private void bindPool(List<?> pool, String prefix) {
        if (pool == null) {
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            Object item = pool.get(i);
            if (item instanceof com.example.iml.orchestrator.integration.binaryrpc.AbstractBinaryRpcSupervisor supervisor) {
                String key = prefix + "_" + i;
                supervisor.setHealthListener(ok -> applySupervisorHealth.accept(key, ok));
            }
        }
    }

    private void watchExternal(String name, Supplier<ExternalServiceProcess> current, BooleanSupplier restart) {
        ExternalServiceProcess process = current.get();
        if (process != null) {
            attachExit(process, name, restart);
        }
        watched.add(new WatchedExternal(name, current, restart));
    }

    void attachExit(ExternalServiceProcess process, String name, BooleanSupplier restart) {
        process.onUnexpectedExit(() -> handleDeath.accept(name, restart));
    }

    void reattachAfterRestart(String name) {
        for (WatchedExternal item : watched) {
            if (!item.name().equals(name)) {
                continue;
            }
            ExternalServiceProcess process = item.current().get();
            if (process != null) {
                attachExit(process, name, item.restart());
            }
            if ("analis_surface".equals(name) && ctx.analisSurfaceProcesses() != null) {
                for (ExternalServiceProcess p : ctx.analisSurfaceProcesses()) {
                    if (p != null) {
                        attachExit(p, name, item.restart());
                    }
                }
            }
        }
    }

    record WatchedExternal(
            String name,
            Supplier<ExternalServiceProcess> current,
            BooleanSupplier restart
    ) {
    }
}
