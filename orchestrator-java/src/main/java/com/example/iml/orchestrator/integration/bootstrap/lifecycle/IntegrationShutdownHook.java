package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Идемпотентное завершение всех дочерних процессов при любом выходе JVM (Ctrl+C, SIGTERM, finally).
 * Регистрируется после успешного старта child-процессов; {@link #run} безопасен из hook и finally.
 */
public final class IntegrationShutdownHook {

    private static final AtomicReference<IntegrationRuntimeContext> CTX = new AtomicReference<>();
    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static volatile boolean hookRegistered;

    private IntegrationShutdownHook() {
    }

    /** Привязать live-контекст и зарегистрировать JVM shutdown hook (один раз). */
    public static void bind(IntegrationRuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        CTX.set(ctx);
        ensureJvmHook();
    }

    /**
     * Полное завершение стека: lights + shutdown coordinator.
     * Идемпотентно — повторные вызовы из hook/finally/coordinator игнорируются.
     */
    public static void run(String reason) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        IntegrationRuntimeContext ctx = CTX.get();
        if (ctx == null) {
            return;
        }
        try {
            LightsShutdown.run(reason);
        } catch (Throwable t) {
            System.err.println("integration shutdown lights (" + reason + "): " + t.getMessage());
        }
        try {
            IntegrationShutdownCoordinator.shutdownAll(ctx.toShutdownResources());
        } catch (Throwable t) {
            System.err.println("integration shutdown coordinator (" + reason + "): " + t.getMessage());
        }
    }

    /** Только для тестов. */
    static void resetForTests() {
        DONE.set(false);
        CTX.set(null);
        hookRegistered = false;
    }

    private static synchronized void ensureJvmHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                run("jvm-shutdown-hook");
            } catch (Throwable t) {
                System.err.println("integration shutdown hook: " + t.getMessage());
            }
        }, "integration-shutdown"));
    }
}
