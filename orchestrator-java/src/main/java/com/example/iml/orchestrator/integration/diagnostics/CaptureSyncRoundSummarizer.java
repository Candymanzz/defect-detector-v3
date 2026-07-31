package com.example.iml.orchestrator.integration.diagnostics;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Formats and logs round summary for {@link CaptureSyncDiagnostics}. */
final class CaptureSyncRoundSummarizer {

    private CaptureSyncRoundSummarizer() {
    }

    static void summarize(Logger log, String channel, long round, CaptureSyncRound state) {
        if (state == null) {
            return;
        }
        List<Integer> missing = new ArrayList<>();
        for (Integer cam : state.expectedCameraIds) {
            if (!state.captureOkByCamera.containsKey(cam) && !state.captureFailed.contains(cam)) {
                missing.add(cam);
            }
        }
        log.info(
                "sync_diag channel={} event=round_summary round={} capture_ok={}/{} ws_sent={} "
                        + "capture_spread_ms={} ws_spread_ms={} missing_cams={} failed_cams={} frames={}",
                channel,
                round,
                state.captureOkByCamera.size(),
                state.expectedCameraIds.size(),
                state.wsSentByCamera.size(),
                state.captureSpreadMs(),
                state.wsSpreadMs(),
                missing,
                List.copyOf(state.captureFailed),
                formatFrames(state.captureOkByCamera)
        );
        if (!missing.isEmpty() || state.captureFailed.size() > 0) {
            log.warn(
                    "sync_diag channel={} event=round_incomplete round={} hint=проблема на бэкенде (capture), не только фронт",
                    channel,
                    round
            );
        } else if (state.wsSpreadMs() > 50L && state.captureSpreadMs() <= 50L) {
            log.warn(
                    "sync_diag channel={} event=round_ws_desync round={} capture_spread_ms={} ws_spread_ms={} "
                            + "hint=кадры на бэкенде почти одновременно, на фронт уходят с разбросом",
                    channel,
                    round,
                    state.captureSpreadMs(),
                    state.wsSpreadMs()
            );
        }
    }

    private static String formatFrames(Map<Integer, Long> framesByCamera) {
        TreeMap<Integer, Long> sorted = new TreeMap<>(framesByCamera);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, Long> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }
}
