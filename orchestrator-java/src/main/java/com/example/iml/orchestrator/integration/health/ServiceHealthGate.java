package com.example.iml.orchestrator.integration.health;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Агрегат здоровья критичных сервисов.
 * {@link #healthyForVision()} — для ПЛК и пайплайна; {@link #IO_INPUT_MONITOR} из него исключён.
 */
public final class ServiceHealthGate {

    /** Падение IoInputMonitor не даёт vision_fault и не останавливает инспекцию (только restart в watchdog). */
    public static final String IO_INPUT_MONITOR = "io_input_monitor";

    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();
    private volatile Runnable onChanged;

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public boolean healthy() {
        return unhealthy.isEmpty();
    }

    /** Здоровье для vision_ready / vision_fault и блокировки пайплайна (без io_input_monitor). */
    public boolean healthyForVision() {
        for (String key : unhealthy) {
            if (!IO_INPUT_MONITOR.equals(key)) {
                return false;
            }
        }
        return true;
    }

    public Set<String> visionBlockingReasons() {
        if (unhealthy.isEmpty()) {
            return Set.of();
        }
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (String key : unhealthy) {
            if (!IO_INPUT_MONITOR.equals(key)) {
                out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public Set<String> unhealthyReasons() {
        return Collections.unmodifiableSet(unhealthy);
    }

    public void markUnhealthy(String name) {
        String key = normalize(name);
        if (key == null) {
            return;
        }
        if (unhealthy.add(key)) {
            fireChanged();
        }
    }

    public void markHealthy(String name) {
        String key = normalize(name);
        if (key == null) {
            return;
        }
        if (unhealthy.remove(key)) {
            fireChanged();
        }
    }

    private void fireChanged() {
        Runnable listener = onChanged;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception ignored) {
            }
        }
    }

    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
