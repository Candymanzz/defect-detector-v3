package com.example.iml.orchestrator.integration.clientws.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Счётчики и накопители задержек для WebSocket-клиента. Потокобезопасно.
 */
public final class ClientWsObservability {

    private final AtomicLong wsDisconnectTotal = new AtomicLong();
    private final AtomicLong referenceBundleAcceptCount = new AtomicLong();
    private final AtomicLong referenceBundleProcessTotalNanos = new AtomicLong();
    private final AtomicLong inspectToClientNotifyCount = new AtomicLong();
    private final AtomicLong inspectToClientLatencyTotalNanos = new AtomicLong();
    private final AtomicLong resetSessionCount = new AtomicLong();

    public void recordWsDisconnect() {
        wsDisconnectTotal.incrementAndGet();
    }

    public void recordReferenceBundleProcessedNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        referenceBundleAcceptCount.incrementAndGet();
        referenceBundleProcessTotalNanos.addAndGet(nanos);
    }

    public void recordCameraInspectionDeliveredNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        inspectToClientNotifyCount.incrementAndGet();
        inspectToClientLatencyTotalNanos.addAndGet(nanos);
    }

    public void recordResetSession() {
        resetSessionCount.incrementAndGet();
    }

    public Map<String, Object> snapshotMap() {
        long bCnt = referenceBundleAcceptCount.get();
        long bSum = referenceBundleProcessTotalNanos.get();
        long iCnt = inspectToClientNotifyCount.get();
        long iSum = inspectToClientLatencyTotalNanos.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ws_disconnect_total", wsDisconnectTotal.get());
        m.put("reference_bundle_accept_count", bCnt);
        m.put("reference_bundle_process_avg_ms", bCnt == 0 ? 0.0 : (bSum / (double) bCnt) / 1_000_000d);
        m.put("inspect_to_client_notify_count", iCnt);
        m.put("inspect_to_client_latency_avg_ms", iCnt == 0 ? 0.0 : (iSum / (double) iCnt) / 1_000_000d);
        m.put("reset_session_count", resetSessionCount.get());
        return m;
    }

    public String summaryForLog() {
        Map<String, Object> m = snapshotMap();
        return "disconnects=" + m.get("ws_disconnect_total")
                + " bundle_n=" + m.get("reference_bundle_accept_count")
                + " bundle_avg_ms=" + m.get("reference_bundle_process_avg_ms")
                + " inspect_client_n=" + m.get("inspect_to_client_notify_count")
                + " inspect_client_avg_ms=" + m.get("inspect_to_client_latency_avg_ms")
                + " reset_n=" + m.get("reset_session_count");
    }
}
