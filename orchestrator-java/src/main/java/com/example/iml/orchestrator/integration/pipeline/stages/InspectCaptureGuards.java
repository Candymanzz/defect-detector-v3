package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.Map;

/**
 * Shared preconditions for geometry / python inspect executors.
 */
final class InspectCaptureGuards {

    private InspectCaptureGuards() {
    }

    static boolean hasValidCaptureFrame(PipelineState state) {
        if (state == null || state.capture() == null || state.capture().header() == null) {
            return false;
        }
        Map<String, Object> h = state.capture().header();
        String shmName = String.valueOf(h.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(h.get("width"), 0);
        int height = YamlScalars.toInt(h.get("height"), 0);
        return !shmName.isEmpty() && width > 0 && height > 0;
    }

    static boolean isPositioningHardFail(PipelineState state) {
        return state != null
                && state.capture() != null
                && state.capture().header() != null
                && YamlScalars.toBool(
                        state.capture().header().get(InspectPositioningExecutor.HEADER_HARD_FAIL),
                        false
                );
    }
}
