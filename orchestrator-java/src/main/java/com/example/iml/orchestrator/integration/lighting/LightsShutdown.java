package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Гашение вспышек и останов LightServer при любом выходе JVM (Ctrl+C, kill, finally).
 * Идемпотентно — hook и {@code finally} могут вызвать дважды.
 */
public final class LightsShutdown {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static final AtomicReference<Logger> LOG = new AtomicReference<>();
    private static final AtomicReference<LightTriggerClient> CLIENT = new AtomicReference<>();
    private static final AtomicReference<ExternalServiceProcess> PROCESS = new AtomicReference<>();
    private static final AtomicReference<IntervalFlashController> INTERVAL_FLASH = new AtomicReference<>();
    private static volatile int lightHttpPort = 5080;
    private static volatile boolean hookRegistered;

    private LightsShutdown() {
    }

    public static void bind(
            Logger log,
            LightTriggerClient client,
            ExternalServiceProcess lightServerProcess,
            int httpPort
    ) {
        LOG.set(log);
        CLIENT.set(client);
        PROCESS.set(lightServerProcess);
        if (httpPort > 0) {
            lightHttpPort = httpPort;
        }
        ensureHook();
    }

    public static void bindIntervalFlash(IntervalFlashController controller) {
        INTERVAL_FLASH.set(controller);
    }

    private static synchronized void ensureHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                run("jvm-shutdown-hook");
            } catch (Throwable t) {
                System.err.println("lights shutdown hook: " + t.getMessage());
            }
        }, "lights-shutdown"));
    }

    /** Вызвать из finally / shutdown coordinator. */
    public static void run(String reason) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        Logger log = LOG.get();
        if (log != null) {
            log.info("lights shutdown ({})", reason);
        }
        IntervalFlashController flash = INTERVAL_FLASH.getAndSet(null);
        if (flash != null) {
            try {
                flash.close();
            } catch (Exception e) {
                warn(log, "interval_flash close: " + e.getMessage());
            }
        }
        LightTriggerClient client = CLIENT.get();
        if (client != null) {
            try {
                if (log != null) {
                    log.info("turning off all lights before stopping LightServer");
                }
                client.forceAllOff();
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                warn(log, "forceAllOff: " + e.getMessage());
            }
            try {
                client.shutdown();
            } catch (Exception e) {
                warn(log, "light client shutdown: " + e.getMessage());
            }
        }
        ExternalServiceProcess process = PROCESS.getAndSet(null);
        if (process != null) {
            try {
                if (log != null) {
                    log.info("stopping LightServer process");
                }
                process.close();
            } catch (Exception e) {
                warn(log, "LightServer process close: " + e.getMessage());
            }
        }
        ExternalServiceProcess.killListenersOnPort(lightHttpPort, log);
    }

    private static void warn(Logger log, String message) {
        if (log != null) {
            log.warn(message);
        } else {
            System.err.println(message);
        }
    }
}
