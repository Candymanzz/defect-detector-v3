package com.example.iml.orchestrator.integration.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * Дополнительный лог этапов пайплайна: JSONL и параллельный .txt с длительностями.
 */
public final class PipelineStagesLog implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(PipelineStagesLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintWriter jsonl;
    private final PrintWriter text;

    public PipelineStagesLog(Path jsonlPath) throws java.io.IOException {
        Files.createDirectories(jsonlPath.getParent());
        this.jsonl = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(jsonlPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)));
        this.text = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(siblingTextPath(jsonlPath), StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)));
    }

    private static Path siblingTextPath(Path jsonlPath) {
        String n = jsonlPath.getFileName().toString();
        String base = n.endsWith(".jsonl") ? n.substring(0, n.length() - 6) : n + "-stages";
        return jsonlPath.resolveSibling(base + ".txt");
    }

    public synchronized void append(Map<String, Object> row) {
        try {
            String iso = Instant.now().toString();
            row.put("ts_utc", iso);
            String json = MAPPER.writeValueAsString(row);
            jsonl.println(json);
            jsonl.flush();
            text.println(formatHumanLine(iso, row));
            text.flush();
        } catch (Exception e) {
            log.warn("pipeline_stages_log write failed: {}", e.getMessage());
        }
    }

    private static String formatHumanLine(String iso, Map<String, Object> row) {
        String event = String.valueOf(row.getOrDefault("event", "?"));
        StringBuilder sb = new StringBuilder();
        sb.append(iso).append(" | ").append(event);
        if ("inspection_cycle".equals(event)) {
            sb.append(" | cam=").append(row.get("camera_id"));
            sb.append(" frame=").append(row.get("frame_id"));
            sb.append(" product=").append(row.get("product_type"));
            if (row.get("conveyor_bucket_index") != null) {
                sb.append(" bucket=").append(row.get("conveyor_bucket_index"));
                if (row.get("conveyor_buckets_total") != null) {
                    sb.append("/").append(row.get("conveyor_buckets_total"));
                }
                sb.append(" photo=").append(row.get("conveyor_photo_index"));
            }
            sb.append(" | capture=").append(fmtSec(row.get("capture_s")));
            sb.append(" geom=").append(fmtSec(row.get("geometry_s")));
            sb.append(" py=").append(fmtSec(row.get("python_worker_s")));
            sb.append(" (align=").append(fmtSec(row.get("py_align_s")));
            sb.append(" diff=").append(fmtSec(row.get("py_diff_s")));
            sb.append(" anomaly=").append(fmtSec(row.get("py_anomaly_s")));
            sb.append(" fp=").append(fmtSec(row.get("py_fp_recheck_s")));
            sb.append(" enc=").append(fmtSec(row.get("py_encode_s")));
            sb.append(" py_int=").append(fmtSec(row.get("py_reported_total_s")));
            sb.append(") decision=").append(fmtSec(row.get("decision_s")));
            sb.append(" fanout=").append(fmtSec(row.get("fanout_s")));
            sb.append(" | ref_this_cycle_ms=").append(row.get("reference_ms"));
            sb.append(" wall_since_cam_thread=").append(fmtSec(row.get("pipeline_wall_since_cam_thread_start_s")));
            sb.append(" | ").append(row.get("action"));
            sb.append(" py=").append(row.get("python_status"));
            sb.append(" geom=").append(row.get("geometry_status"));
            sb.append(" score=").append(row.get("anomaly_score"));
        } else if ("reference_capture".equals(event)) {
            sb.append(" | cam=").append(row.get("camera_id"));
            sb.append(" product=").append(row.get("product_type"));
            if (row.get("conveyor_bucket_index") != null) {
                sb.append(" bucket=").append(row.get("conveyor_bucket_index"));
            }
            sb.append(" | reference_wall=").append(fmtSec(row.get("reference_wall_s")));
            sb.append(" set_ref_repeats=").append(row.get("reference_repeats_applied"));
        }
        return sb.toString();
    }

    private static String fmtSec(Object sec) {
        if (sec instanceof Number n) {
            return String.format("%.3fs", n.doubleValue());
        }
        return String.valueOf(sec);
    }

    @Override
    public void close() {
        try {
            jsonl.close();
        } catch (Exception ignored) {
        }
        try {
            text.close();
        } catch (Exception ignored) {
        }
    }
}
