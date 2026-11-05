package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.camera.NativeCameraEvents.Event;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static com.example.iml.orchestrator.integration.camera.NativeCameraEvents.*;

/** One DI window, all participating cameras. Caller serializes DI and camera events. */
final class ExposureEndFlashGate {
    private final Set<Integer> cameras;
    private final int validationCycles;
    private final Map<Integer, Long> starts = new HashMap<>();
    private final Map<Integer, Long> lastTicks = new HashMap<>();
    private final Map<Integer, Long> dropped = new HashMap<>();
    private final Set<Integer> ended = new HashSet<>();
    private long windowStart;
    private boolean active, invalid, complete;
    private int consecutive;

    ExposureEndFlashGate(Set<Integer> cameras, int validationCycles) {
        if (cameras.isEmpty() || validationCycles < 1) throw new IllegalArgumentException("ExposureEnd needs cameras and validation cycles >= 1");
        this.cameras = Set.copyOf(cameras);
        this.validationCycles = validationCycles;
    }

    void begin(long now) {
        windowStart = now;
        starts.clear(); ended.clear();
        active = true; invalid = false; complete = false;
    }

    /** Validation is committed only at the end of a window, including any late queue-loss reports. */
    boolean finish() {
        if (!active) return false;
        boolean good = complete && !invalid;
        consecutive = good ? Math.min(validationCycles, consecutive + 1) : 0;
        active = false;
        return good;
    }

    /** True once, on the last matching end of a validated window. */
    boolean accept(Event e) {
        if (!cameras.contains(e.cameraId())) return false;
        Long previousDrop = dropped.put(e.cameraId(), e.dropped());
        if ((previousDrop != null && e.dropped() != previousDrop) || (previousDrop == null && e.dropped() > 0)) {
            consecutive = 0;
            if (active) invalid = true;
        }
        if (!active || invalid || e.hostMonotonicNs() < windowStart ||
                (e.kind() != EXPOSURE_START && e.kind() != EXPOSURE_END)) return false;
        // Reject replays and out-of-order notifications within this camera session.
        Long previousTick = lastTicks.get(e.cameraId());
        if (previousTick != null && e.deviceTicks() <= previousTick) return false;
        lastTicks.put(e.cameraId(), e.deviceTicks());
        if (e.kind() == EXPOSURE_START) {
            if (complete) { invalid = true; consecutive = 0; return false; }
            starts.put(e.cameraId(), e.deviceTicks());
            ended.remove(e.cameraId());
        } else {
            Long start = starts.get(e.cameraId());
            if (start == null || e.deviceTicks() <= start || !ended.add(e.cameraId())) return false;
            if (ended.containsAll(cameras)) {
                complete = true;
                return consecutive >= validationCycles;
            }
        }
        return false;
    }

    int consecutive() { return consecutive; }
    int requiredCycles() { return validationCycles; }
}
