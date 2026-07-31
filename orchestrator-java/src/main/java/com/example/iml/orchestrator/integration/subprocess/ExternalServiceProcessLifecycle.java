package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Soft/force stop helpers for an external OS process tree. */
final class ExternalServiceProcessLifecycle {

    private ExternalServiceProcessLifecycle() {
    }

    static void close(
            String name,
            Process process,
            AtomicBoolean closing,
            LongSupplier pidSupplier,
            Logger log
    ) {
        closing.set(true);
        try {
            if (!process.isAlive()) {
                return;
            }
            long pid = pidSupplier.getAsLong();
            log.info("stopping external service {} pid={}", name, pid);
            // Сначала мягко по дереву (dotnet часто оставляет дочерние процессы).
            destroyProcessTree(process, false);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("external service {} pid={} did not exit in 5s, forcing tree kill", name, pid);
                destroyProcessTree(process, true);
                if (!process.waitFor(3, TimeUnit.SECONDS) && ExternalServicePortKiller.isWindows() && pid > 0) {
                    ExternalServicePortKiller.taskkillWindows(pid, true);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            if (process.isAlive()) {
                log.warn("external service {} pid={} still alive after force kill", name, pid);
            } else {
                log.info("external service {} stopped", name);
            }
        } catch (Exception e) {
            log.warn("failed to stop external service {}: {}", name, e.getMessage());
        }
    }

    static void destroyProcessTree(Process process, boolean forcibly) {
        try {
            ProcessHandle handle = process.toHandle();
            handle.descendants().forEach(child -> {
                try {
                    if (forcibly) {
                        child.destroyForcibly();
                    } else {
                        child.destroy();
                    }
                } catch (Exception ignored) {
                }
            });
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (Exception e) {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }
}
