package com.example.iml.orchestrator.integration.logging;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Monotonic trigger-to-logical-verdict timing for one orchestrator run. */
public final class TriggerVerdictLog {
    private static final Logger log = LogManager.getLogger(TriggerVerdictLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_UNRESOLVED_SEQUENCES = 10_000;

    private static final class TriggerStart {
        private final Instant receivedAt;
        private final long receivedNanos;
        private final String source;
        private final Map<Integer, Boolean> groupTimeouts = new ConcurrentHashMap<>();

        private TriggerStart(Instant receivedAt, long receivedNanos, String source) {
            this.receivedAt = receivedAt;
            this.receivedNanos = receivedNanos;
            this.source = source;
        }
    }

    private final Path file;
    private final Map<Long, TriggerStart> starts = new ConcurrentHashMap<>();
    private boolean writable = true;

    public TriggerVerdictLog(Path sessionDirectory) {
        this.file = sessionDirectory == null ? null : sessionDirectory.resolve("trigger-verdict.jsonl");
    }

    public void triggerReceived(long sequence, Instant receivedAt, long receivedNanos,
                                String source, List<Integer> cameraIds) {
        if (file == null || sequence <= 0) {
            return;
        }
        Instant timestamp = receivedAt == null ? Instant.now() : receivedAt;
        long nanos = receivedNanos == 0L ? System.nanoTime() : receivedNanos;
        if (starts.putIfAbsent(sequence, new TriggerStart(timestamp, nanos, source)) != null) {
            return;
        }
        Map<String, Object> row = base("trigger_received", sequence, timestamp);
        row.put("source", source);
        row.put("camera_ids", cameraIds == null ? List.of() : List.copyOf(cameraIds));
        row.put("measurement_start", "orchestrator_received_trigger");
        append(row);
        if (sequence > MAX_UNRESOLVED_SEQUENCES && sequence % 256 == 0) {
            starts.keySet().removeIf(old -> old < sequence - MAX_UNRESOLVED_SEQUENCES);
        }
    }

    public void triggerSkipped(long sequence, String reason) {
        TriggerStart start = starts.remove(sequence);
        if (file == null || sequence <= 0) {
            return;
        }
        Map<String, Object> row = base("trigger_skipped", sequence, Instant.now());
        row.put("source", start == null ? null : start.source);
        row.put("reason", reason);
        putElapsed(row, start);
        append(row);
    }

    public void bucketVerdict(long sequence, int groupId, boolean pass, boolean timedOut,
                              int framesReceived, int framesExpected, List<Integer> rejectCameras) {
        if (file == null || sequence <= 0) {
            return;
        }
        TriggerStart start = starts.get(sequence);
        if (start != null) {
            start.groupTimeouts.put(groupId, timedOut);
        }
        Map<String, Object> row = base("bucket_verdict", sequence, Instant.now());
        row.put("source", start == null ? null : start.source);
        row.put("group_id", groupId);
        row.put("overall_pass", pass);
        row.put("timed_out", timedOut);
        row.put("frames_received", framesReceived);
        row.put("frames_expected", framesExpected);
        row.put("reject_cameras", rejectCameras);
        putElapsed(row, start);
        append(row);
    }

    /** Called when every bucket result is ready, before asynchronous PLC/UI fan-out. */
    public void verdictReady(long sequence, List<BucketFanOutResult> results) {
        if (file == null || sequence <= 0 || results == null || results.isEmpty()) {
            return;
        }
        TriggerStart start = starts.remove(sequence);
        boolean overallPass = results.stream().allMatch(BucketFanOutResult::overallPass);
        boolean hasFrames = results.stream().anyMatch(result -> !result.frameDecisions().isEmpty());
        boolean noInspection = overallPass && !hasFrames;
        boolean captureOnly = hasFrames && results.stream()
                .flatMap(result -> result.frameDecisions().values().stream())
                .allMatch(decision -> decision != null && "CAPTURE".equals(decision.action()));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (BucketFanOutResult result : results) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("group_id", result.groupId());
            group.put("overall_pass", result.overallPass());
            group.put("frames_received", result.frameDecisions().size());
            group.put("frames_expected", result.bucketCameraIds().size());
            boolean hasBucketEvent = start != null && start.groupTimeouts.containsKey(result.groupId());
            group.put("timed_out", hasBucketEvent ? start.groupTimeouts.get(result.groupId())
                    : result.frameDecisions().isEmpty() ? true : null);
            group.put("synthetic_missing_group", !hasBucketEvent && result.frameDecisions().isEmpty());
            groups.add(group);
        }
        Map<String, Object> row = base("verdict_ready", sequence, Instant.now());
        row.put("source", start == null ? null : start.source);
        row.put("verdict", noInspection ? "NO_INSPECTION" : captureOnly ? "CAPTURE_ONLY"
                : overallPass ? "GOOD" : "REJECT");
        row.put("verdict_ru", noInspection ? "НЕТ ИНСПЕКЦИИ" : captureOnly ? "ТОЛЬКО СЪЁМКА"
                : overallPass ? "ГОДЕН" : "НЕ ГОДЕН");
        row.put("overall_pass", overallPass);
        row.put("capture_only", captureOnly);
        row.put("no_inspection", noInspection);
        row.put("groups", groups);
        row.put("measurement_end", "logical_verdict_before_plc_ui_fanout");
        putElapsed(row, start);
        append(row);
    }

    private static Map<String, Object> base(String event, long sequence, Instant timestamp) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", timestamp.toString());
        row.put("event", event);
        row.put("trigger_sequence", sequence);
        return row;
    }

    private static void putElapsed(Map<String, Object> row, TriggerStart start) {
        row.put("start_missing", start == null);
        row.put("trigger_received_at", start == null ? null : start.receivedAt.toString());
        row.put("duration_ms", start == null ? null
                : Math.max(0.0, (System.nanoTime() - start.receivedNanos) / 1_000_000.0));
    }

    private synchronized void append(Map<String, Object> row) {
        if (file == null || !writable) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            writable = false;
            log.warn("Trigger-to-verdict trace disabled after write failure: {}", e.getMessage());
        }
    }
}
