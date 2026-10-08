package com.example.iml.orchestrator.integration.health;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Агрегат здоровья критичных сервисов.
 * {@link #healthyForVision()} — для ПЛК и пайплайна.
 */
public final class ServiceHealthGate {

    private final Set<String> unhealthy = ConcurrentHashMap.newKeySet();
    private final List<Runnable> onChangedListeners = new CopyOnWriteArrayList<>();

    /** Заменяет всех слушателей одним (тесты / legacy). */
    public void setOnChanged(Runnable onChanged) {
        onChangedListeners.clear();
        addOnChanged(onChanged);
    }

    public void addOnChanged(Runnable onChanged) {
        if (onChanged != null) {
            onChangedListeners.add(onChanged);
        }
    }

    /** Участвует ли сервис в vision_ready / vision_fault на ПЛК. */
    public static boolean affectsVisionPlc(String name) {
        String key = normalize(name);
        return key != null;
    }

    public boolean healthy() {
        return unhealthy.isEmpty();
    }

    /** Здоровье для vision_ready / vision_fault и блокировки пайплайна. */
    public boolean healthyForVision() {
        return healthy();
    }

    public Set<String> visionBlockingReasons() {
        return unhealthyReasons();
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
        for (Runnable listener : onChangedListeners) {
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
