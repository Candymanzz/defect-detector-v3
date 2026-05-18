package com.example.iml.orchestrator.integration.clientws.session;

import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory контекст эталонов и FP. Без persist на диск.
 */
public final class ClientWsReferenceContext {

    private record FpZonesOverride(int heatmapWidth, int heatmapHeight, List<FpZoneNorm> zones) {
    }

    private final AtomicReference<ReferenceBundleSnapshot> bundle = new AtomicReference<>();
    private final AtomicReference<FpZonesOverride> fpOverride = new AtomicReference<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean bundleCommitted = new AtomicBoolean(false);
    private final AtomicInteger activeReferenceViewIndex = new AtomicInteger(0);

    public void applyBundle(ReferenceBundleSnapshot snapshot) {
        bundle.set(snapshot);
        fpOverride.set(null);
        dirty.set(false);
        bundleCommitted.set(true);
        activeReferenceViewIndex.set(0);
    }

    public void clear() {
        bundle.set(null);
        fpOverride.set(null);
        dirty.set(false);
        bundleCommitted.set(false);
        activeReferenceViewIndex.set(0);
    }

    public Optional<ReferenceBundleSnapshot> snapshot() {
        return Optional.ofNullable(bundle.get());
    }

    public boolean hasCommittedBundle() {
        return bundleCommitted.get();
    }

    public boolean dirty() {
        return dirty.get();
    }

    public void markDirty() {
        dirty.set(true);
    }

    public int activeReferenceViewIndex() {
        return activeReferenceViewIndex.get();
    }

    public void setActiveReferenceViewIndex(int index) {
        if (index >= 0 && index < 5) {
            activeReferenceViewIndex.set(index);
        }
    }

    public void applyFpZonesHotUpdate(int heatmapWidth, int heatmapHeight, List<FpZoneNorm> zones) {
        fpOverride.set(new FpZonesOverride(heatmapWidth, heatmapHeight, List.copyOf(zones)));
        markDirty();
    }

    public List<FpZoneNorm> effectiveFpZones() {
        FpZonesOverride o = fpOverride.get();
        if (o != null) {
            return o.zones();
        }
        return snapshot().map(ReferenceBundleSnapshot::fpZones).orElse(List.of());
    }

    public int effectiveHeatmapWidth() {
        FpZonesOverride o = fpOverride.get();
        if (o != null) {
            return o.heatmapWidth();
        }
        return snapshot().map(ReferenceBundleSnapshot::heatmapWidth).orElse(0);
    }

    public int effectiveHeatmapHeight() {
        FpZonesOverride o = fpOverride.get();
        if (o != null) {
            return o.heatmapHeight();
        }
        return snapshot().map(ReferenceBundleSnapshot::heatmapHeight).orElse(0);
    }
}
