package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import org.apache.logging.log4j.Logger;

import java.util.List;

/** Direction / camera-target helpers for IO-input line capture. */
final class IoInputCaptureDirectionGate {

    private final Logger log;
    private final IoInputDiscreteConfig ioInputConfig;
    private final IoInputLiveState live;

    IoInputCaptureDirectionGate(Logger log, IoInputDiscreteConfig ioInputConfig, IoInputLiveState live) {
        this.log = log;
        this.ioInputConfig = ioInputConfig;
        this.live = live;
    }

    /**
     * UI «Прямой/Обратный» не фильтрует. При {@code require_direction} — DI2=1
     * (или уже latched после первого DI2=1).
     */
    boolean allowsCaptureForSelectedDirection() {
        if (!ioInputConfig.requireDirection()) {
            return true;
        }
        if (ioInputConfig.directionLatch() ? live.directionLatched : live.directionActive) {
            return true;
        }
        log.info("io_input_trigger skip: DI2=0 (need direction before DI3 capture)");
        return false;
    }

    String effectiveDirectionWire() {
        if (ioInputConfig.directionLatch() && live.directionLatched) {
            return "forward";
        }
        return live.directionActive ? "forward" : "reverse";
    }

    String directionSourceLabel() {
        return "di2";
    }

    /** Всегда все камеры; фильтр — только направление хода (UI ↔ DI2), не группа. */
    List<Integer> resolveTargetCameras() {
        return null;
    }

    static String formatCameraTarget(List<Integer> targetCameras) {
        if (targetCameras == null || targetCameras.isEmpty()) {
            return "all";
        }
        return targetCameras.toString();
    }

    boolean passDebounce() {
        if (ioInputConfig.debounceMs() <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - live.lastFireMs < ioInputConfig.debounceMs()) {
            log.debug("io_input_trigger debounced");
            return false;
        }
        live.lastFireMs = now;
        return true;
    }
}
