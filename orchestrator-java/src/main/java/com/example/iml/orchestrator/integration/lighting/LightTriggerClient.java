package com.example.iml.orchestrator.integration.lighting;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Параллельный HTTP-триггер всех enabled endpoints LightServer.v3 (COM IO + MV-LE).
 * Яркость 0…100% задаётся глобально и отдельно по {@code id} каждого endpoint.
 */
public final class LightTriggerClient {

    private static final Logger LOG = LogManager.getLogger(LightTriggerClient.class);
    private static final int MAX_TRIGGER_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 200L;

    private final boolean enabled;
    private final boolean failOnError;
    private final int defaultBrightnessPercent;
    private final int durationMs;
    private final int timeoutMs;
    private final int settleDelayMs;
    private final List<EndpointRuntime> endpoints;
    private final ExecutorService triggerExecutor;
    /** Один POST за раз — live_preview и capture не держат COM2 параллельно. */
    private final Object lightCommandLock = new Object();

    private static final class EndpointRuntime {
        final LightEndpoint endpoint;
        volatile int brightnessPercent;
        final int[] brightnessRaw;

        EndpointRuntime(LightEndpoint endpoint, int brightnessPercent, int[] brightnessRaw) {
            this.endpoint = endpoint;
            this.brightnessPercent = brightnessPercent;
            this.brightnessRaw = brightnessRaw;
        }
    }

    public static LightTriggerClient fromRootYaml(Map<String, Object> root) {
        return new LightTriggerClient(LightServersConfig.fromRootYaml(root));
    }

    public LightTriggerClient(LightServersConfig cfg) {
        this.enabled = cfg.enabled() && !cfg.endpoints().isEmpty();
        this.failOnError = cfg.failOnError();
        this.defaultBrightnessPercent = cfg.brightnessPercent();
        this.durationMs = cfg.durationMs();
        this.timeoutMs = Math.max(100, cfg.timeoutMs());
        this.settleDelayMs = Math.max(0, cfg.settleDelayMs());
        this.endpoints = buildEndpoints(cfg);
        int n = Math.max(1, (int) endpoints.stream().filter(r -> r.endpoint.enabled()).count());
        this.triggerExecutor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "light-trigger");
            t.setDaemon(true);
            return t;
        });
        if (enabled) {
            LOG.info("light_servers: {} endpoint(s) default_brightness_percent={} duration_ms={}",
                    endpoints.size(), defaultBrightnessPercent, durationMs);
            for (EndpointRuntime r : endpoints) {
                if (r.endpoint.enabled()) {
                    LOG.info("  light endpoint id={} type={} brightness_percent={}",
                            r.endpoint.id(), r.endpoint.getClass().getSimpleName(), r.brightnessPercent);
                }
            }
        }
    }

    private static List<EndpointRuntime> buildEndpoints(LightServersConfig cfg) {
        List<EndpointRuntime> list = new ArrayList<>();
        for (LightServersConfig.EndpointSpec spec : cfg.endpoints()) {
            if (!spec.enabled()) {
                continue;
            }
            LightEndpoint ep = switch (spec.type()) {
                case COM_IO -> new ComIoLightEndpoint(
                        LOG,
                        spec.id(),
                        true,
                        spec.baseUrl(),
                        spec.comPort(),
                        spec.comPortsQuery(),
                        cfg.timeoutMs(),
                        spec.channels(),
                        spec.brightnessRaw()
                );
                case MV_LE -> new MvLeLightEndpoint(
                        LOG,
                        spec.id(),
                        true,
                        spec.baseUrl(),
                        cfg.timeoutMs(),
                        spec.deviceIndex(),
                        spec.channels()
                );
            };
            list.add(new EndpointRuntime(ep, spec.brightnessPercent(), spec.brightnessRaw()));
        }
        return List.copyOf(list);
    }

    /** Яркость по умолчанию (глобальная из конфига / для всех endpoints). */
    public int brightnessPercent() {
        return defaultBrightnessPercent;
    }

    public int brightnessPercent(String endpointId) {
        EndpointRuntime r = find(endpointId);
        return r == null ? defaultBrightnessPercent : r.brightnessPercent;
    }

    public Map<String, Integer> brightnessByEndpoint() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (EndpointRuntime r : endpoints) {
            if (r.endpoint.enabled()) {
                out.put(r.endpoint.id(), r.brightnessPercent);
            }
        }
        return Map.copyOf(out);
    }

    public List<String> endpointIds() {
        return endpoints.stream().filter(r -> r.endpoint.enabled()).map(r -> r.endpoint.id()).toList();
    }

    /** Установить одну яркость для всех enabled endpoints. */
    public void setBrightnessPercent(int percent) {
        int clamped = LightBrightnessScale.clampPercent(percent);
        for (EndpointRuntime r : endpoints) {
            if (r.endpoint.enabled()) {
                int before = r.brightnessPercent;
                r.brightnessPercent = clamped;
                if (before != clamped) {
                    LOG.info("light {} brightness {}% -> {}%", r.endpoint.id(), before, clamped);
                }
            }
        }
    }

    public void setBrightnessPercent(String endpointId, int percent) {
        EndpointRuntime r = find(endpointId);
        if (r == null) {
            throw new IllegalArgumentException("unknown light endpoint id: " + endpointId);
        }
        int clamped = LightBrightnessScale.clampPercent(percent);
        int before = r.brightnessPercent;
        r.brightnessPercent = clamped;
        if (before != clamped) {
            LOG.info("light {} brightness {}% -> {}%", endpointId, before, clamped);
        }
    }

    public void trigger(int cameraId, long frameId, String phase) {
        lightOn(cameraId, frameId, phase);
    }

    /**
     * Цикл съёмки: {@code POST ... lightControllerSource=On} → пауза → capture → {@code Off}.
     * Иначе MV-LE/COM остаются в On и подсветка горит постоянно.
     */
    public void runCaptureWithLighting(
            int cameraId,
            long frameId,
            String phase,
            int flashLeadMs,
            CaptureStep captureStep
    ) throws Exception {
        if (!enabled) {
            captureStep.run();
            return;
        }
        synchronized (lightCommandLock) {
            if (!runSourceWithRetriesLocked(cameraId, frameId, phase, true)) {
                LOG.warn("light On failed cam={} phase={} — skip Off and capture without lighting", cameraId, phase);
                captureStep.run();
                return;
            }
            if (flashLeadMs > 0) {
                Thread.sleep(flashLeadMs);
            }
            try {
                captureStep.run();
            } finally {
                runSourceWithRetriesLocked(cameraId, frameId, phase, false);
                sleepSettle();
            }
        }
    }

    @FunctionalInterface
    public interface CaptureStep {
        void run() throws Exception;
    }

    /** {@code lightControllerSource=On} на все enabled endpoints (LightServer.v3). */
    public boolean lightOn(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return false;
        }
        LOG.info("light On cam={} frame={} phase={} brightness={}",
                cameraId, frameId, phase, brightnessByEndpoint());
        return runSourceWithRetries(cameraId, frameId, phase, true);
    }

    /** {@code lightControllerSource=Off} — погасить после съёмки. */
    public void lightOff(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return;
        }
        LOG.info("light Off cam={} frame={} phase={}", cameraId, frameId, phase);
        runSourceWithRetries(cameraId, frameId, phase, false);
    }

    private boolean runSourceWithRetries(int cameraId, long frameId, String phase, boolean on) {
        synchronized (lightCommandLock) {
            return runSourceWithRetriesLocked(cameraId, frameId, phase, on);
        }
    }

    private boolean runSourceWithRetriesLocked(int cameraId, long frameId, String phase, boolean on) {
        for (EndpointRuntime r : endpoints) {
            if (r.endpoint.enabled()) {
                r.endpoint.ensureReady();
            }
        }
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                if (on) {
                    triggerAllParallel(cameraId, frameId, phase, durationMs);
                    sleepSettle();
                } else {
                    turnOffAllParallel();
                }
                return true;
            } catch (RuntimeException e) {
                lastError = e;
            }
            if (attempt < MAX_TRIGGER_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastError == null) {
            return !on;
        }
        if (failOnError && on) {
            throw lastError;
        }
        LOG.warn("light {} failed cam={} phase={}: {}", on ? "On" : "Off", cameraId, phase, lastError.getMessage());
        return false;
    }

    private void turnOffAllParallel() {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (EndpointRuntime r : endpoints) {
            if (!r.endpoint.enabled()) {
                continue;
            }
            tasks.add(() -> {
                r.endpoint.turnOffAll();
                return null;
            });
        }
        if (tasks.isEmpty()) {
            return;
        }
        try {
            List<Future<Void>> futures = triggerExecutor.invokeAll(tasks);
            List<String> errors = new ArrayList<>();
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    errors.add(c.getMessage() != null ? c.getMessage() : c.toString());
                }
            }
            if (!errors.isEmpty()) {
                throw new IllegalStateException("light Off failed: " + String.join("; ", errors));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("light Off interrupted", e);
        }
    }

    private void triggerAllParallel(int cameraId, long frameId, String phase, int durationMs) {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (EndpointRuntime r : endpoints) {
            if (!r.endpoint.enabled()) {
                continue;
            }
            int brightness = r.brightnessPercent;
            tasks.add(() -> {
                r.endpoint.trigger(cameraId, frameId, phase, brightness, durationMs);
                return null;
            });
        }
        if (tasks.isEmpty()) {
            return;
        }
        try {
            List<Future<Void>> futures = triggerExecutor.invokeAll(tasks);
            List<String> errors = new ArrayList<>();
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    errors.add(c.getMessage() != null ? c.getMessage() : c.toString());
                }
            }
            if (!errors.isEmpty()) {
                throw new IllegalStateException("light trigger failed: " + String.join("; ", errors));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("light trigger interrupted", e);
        }
    }

    private EndpointRuntime find(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            return null;
        }
        for (EndpointRuntime r : endpoints) {
            if (endpointId.equals(r.endpoint.id())) {
                return r;
            }
        }
        return null;
    }

    private void sleepSettle() {
        if (settleDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(settleDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void forceAllOff() {
        if (!enabled) {
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>();
        for (EndpointRuntime r : endpoints) {
            if (!r.endpoint.enabled()) {
                continue;
            }
            tasks.add(() -> {
                try {
                    r.endpoint.turnOffAll();
                } catch (Exception e) {
                    LOG.warn("light {} turnOffAll: {}", r.endpoint.id(), e.getMessage());
                }
                return null;
            });
        }
        if (tasks.isEmpty()) {
            return;
        }
        try {
            triggerExecutor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("light forceAllOff interrupted");
        }
    }

    public void shutdown() {
        forceAllOff();
        triggerExecutor.shutdownNow();
        MvLeLightEndpoint.shutdownScheduler();
        try {
            if (!triggerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                triggerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            triggerExecutor.shutdownNow();
        }
    }
}
