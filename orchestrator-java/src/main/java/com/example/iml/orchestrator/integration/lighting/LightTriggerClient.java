package com.example.iml.orchestrator.integration.lighting;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Параллельный HTTP-триггер всех настроенных LightServer / LightServerv.v2.
 * Яркость в конфиге и {@link #setBrightnessPercent(int)} — единая шкала 0…100%.
 */
public final class LightTriggerClient {

    private static final Logger LOG = LogManager.getLogger(LightTriggerClient.class);
    private static final int MAX_TRIGGER_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 200L;

    private final boolean enabled;
    private final boolean failOnError;
    private final int brightnessPercent;
    private final int durationMs;
    private final int settleDelayMs;
    private final List<LightEndpoint> endpoints;
    private final ExecutorService triggerExecutor;
    private volatile int runtimeBrightnessPercent;

    public static LightTriggerClient fromRootYaml(Map<String, Object> root) {
        return new LightTriggerClient(LightServersConfig.fromRootYaml(root));
    }

    /** @deprecated используйте {@link #fromRootYaml(Map)} */
    @Deprecated
    public static LightTriggerClient fromLightServerYaml(Map<String, Object> lightCfg) {
        Map<String, Object> root = lightCfg == null ? Map.of() : Map.of("light_server", lightCfg);
        return fromRootYaml(root);
    }

    public LightTriggerClient(LightServersConfig cfg) {
        this.enabled = cfg.enabled() && !cfg.endpoints().isEmpty();
        this.failOnError = cfg.failOnError();
        this.brightnessPercent = cfg.brightnessPercent();
        this.runtimeBrightnessPercent = cfg.brightnessPercent();
        this.durationMs = cfg.durationMs();
        this.settleDelayMs = Math.max(0, cfg.settleDelayMs());
        this.endpoints = buildEndpoints(cfg);
        int n = Math.max(1, (int) endpoints.stream().filter(LightEndpoint::enabled).count());
        this.triggerExecutor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "light-trigger");
            t.setDaemon(true);
            return t;
        });
        if (enabled) {
            LOG.info("light_servers: {} endpoint(s) brightness_percent={} duration_ms={}",
                    endpoints.size(), brightnessPercent, durationMs);
            for (LightEndpoint ep : endpoints) {
                if (ep.enabled()) {
                    LOG.info("  light endpoint id={} type={}", ep.id(), ep.getClass().getSimpleName());
                }
            }
        }
    }

    private static List<LightEndpoint> buildEndpoints(LightServersConfig cfg) {
        List<LightEndpoint> list = new ArrayList<>();
        for (LightServersConfig.EndpointSpec spec : cfg.endpoints()) {
            if (!spec.enabled()) {
                continue;
            }
            LightEndpoint ep = switch (spec.type()) {
                case TRIGGER_INSPECTION -> new TriggerInspectionLightEndpoint(
                        LOG,
                        spec.id(),
                        true,
                        spec.baseUrl(),
                        spec.triggerPath(),
                        spec.statusPath(),
                        cfg.timeoutMs()
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
            list.add(ep);
        }
        return List.copyOf(list);
    }

    /**
     * Единая яркость для всех контроллеров (0…100). Для будущего client API.
     */
    public void setBrightnessPercent(int percent) {
        this.runtimeBrightnessPercent = LightBrightnessScale.clampPercent(percent);
    }

    public int brightnessPercent() {
        return runtimeBrightnessPercent;
    }

    public void trigger(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return;
        }
        int brightness = runtimeBrightnessPercent;
        for (LightEndpoint ep : endpoints) {
            if (ep.enabled()) {
                ep.ensureReady();
            }
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                triggerAllParallel(cameraId, frameId, phase, brightness, durationMs);
                sleepSettle();
                return;
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
            return;
        }
        if (failOnError) {
            throw lastError;
        }
        LOG.warn("light trigger: {}", lastError.getMessage());
    }

    private void triggerAllParallel(int cameraId, long frameId, String phase, int brightness, int durationMs) {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (LightEndpoint ep : endpoints) {
            if (!ep.enabled()) {
                continue;
            }
            tasks.add(() -> {
                ep.trigger(cameraId, frameId, phase, brightness, durationMs);
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

    public void shutdown() {
        triggerExecutor.shutdownNow();
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
