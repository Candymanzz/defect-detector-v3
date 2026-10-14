package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/** One software timer pulse per fault episode; retries only unaccepted commands. */
public final class IoFaultPublisher implements AutoCloseable {
    private final String timer, line;
    private final ScheduledExecutorService retry;
    private IoRejectPublisher.TimerWriter writer;
    private boolean active, delivered, closed;

    public static IoFaultPublisher fromConfig(Map<String, Object> integration) {
        Map<?, ?> cfg = integration.get("io_fault") instanceof Map<?, ?> m ? m : Map.of();
        if (!YamlScalars.toBool(cfg.get("enabled"), false)) return null;
        String timer = Objects.toString(cfg.get("timer"), "");
        String line = Objects.toString(cfg.get("line"), "");
        if (!timer.matches("Timer[1-8]") || timer.equals("Timer5")
                || !line.matches("Out[1-8]") || line.equals("Out5"))
            throw new IllegalArgumentException("io_fault: specify non-camera timer and output");
        var nativeConfig = MvsIoConfig.parse(integration);
        if (!nativeConfig.enabled() || !nativeConfig.backend().equals("mv_camera"))
            throw new IllegalArgumentException("io_fault requires enabled mv_camera IO");
        for (var output : nativeConfig.reject().groups().values())
            if (output.timer().equals(timer) || output.line().equals(line))
                throw new IllegalArgumentException("io_fault conflicts with reject output");
        int retryMs = YamlScalars.toInt(cfg.get("retry_ms"), 1000);
        if (retryMs < 1) throw new IllegalArgumentException("io_fault.retry_ms must be positive");
        return new IoFaultPublisher(timer, line, retryMs);
    }

    IoFaultPublisher(String timer, String line, int retryMs) {
        this.timer = timer; this.line = line;
        retry = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "io-fault-timer"); t.setDaemon(true); return t;
        });
        retry.scheduleWithFixedDelay(this::deliver, retryMs, retryMs, TimeUnit.MILLISECONDS);
    }
    public synchronized void bind(IoRejectPublisher.TimerWriter writer) { this.writer = writer; deliver(); }
    public synchronized void signal(boolean fault) {
        if (!fault) delivered = false;
        active = fault;
        deliver();
    }
    synchronized void deliver() {
        if (closed || !active || delivered || writer == null) return;
        try {
            writer.trigger(timer, line);
            delivered = true;
            LogManager.getLogger(getClass()).info("io event=fault_timer timer={} line={}", timer, line);
        } catch (RuntimeException e) {
            LogManager.getLogger(getClass()).warn("io event=fault_timer_failed timer={} line={}: {}", timer, line, e.getMessage());
        }
    }
    @Override public synchronized void close() { closed = true; retry.shutdownNow(); }
}
