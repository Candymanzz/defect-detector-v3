package com.example.iml.orchestrator.integration.health;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Агрегат здоровья критичных сервисов: unhealthy → PLC vision_fault + gated vision_ready.
 */
public final class ServiceHealthGate {

    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();
    private volatile Runnable onChanged;

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public boolean healthy() {
        return unhealthy.isEmpty();
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
