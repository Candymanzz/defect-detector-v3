package com.example.iml.orchestrator.integration.smoke;

import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bench smoke: замеряет, успевает ли живой оркестратор выдать вердикт по 2 вёдрам Omron за 4 с.
 *
 * <p>Требует запущенный оркестратор. Запуск:
 * {@code mvn -q test-compile exec:java -Dexec.mainClass=com.example.iml.orchestrator.integration.smoke.BucketTimingSmokeMain -Dexec.classpathScope=test -Dexec.args=".."}
 */
public final class BucketTimingSmokeMain {

    private static final Logger log = LogManager.getLogger(BucketTimingSmokeMain.class);
    private static final long DEFAULT_SLA_MS = 4000L;
    private static final Pattern BUCKET_COMPLETE = Pattern.compile(
            "inspection bucket complete seq=(\\d+) group=(\\d+)"
    );

    private BucketTimingSmokeMain() {
    }

    @SuppressWarnings("unchecked")
    public static List<SmokeResult> run(Map<String, Object> root, Path projectRoot) throws Exception {
        List<SmokeResult> results = new ArrayList<>();

        if (!orchestratorReachable()) {
            results.add(SmokeResult.skip("timing", "orchestrator", "orchestrator not reachable at http://127.0.0.1:8099/health"));
            return results;
        }
        results.add(SmokeResult.ok("timing", "orchestrator", "health OK"));

        Object integrationObj = root.get("integration");
        Map<String, Object> integration = integrationObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        BucketInspectionConfig bucketCfg = BucketInspectionConfig.parse(integration, Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        if (!bucketCfg.enabled() || bucketCfg.groups().size() < 2) {
            results.add(SmokeResult.skip("timing", "config", "inspection_bucket needs 2 groups (ten_cameras)"));
            return results;
        }

        long slaMs = Math.max(DEFAULT_SLA_MS, bucketCfg.timeoutMs());
        Path logFile = projectRoot.resolve("orchestrator-java/logs/orchestrator-runtime.log");
        if (!Files.isRegularFile(logFile)) {
            results.add(SmokeResult.skip("timing", "log", "log file not found: " + logFile));
            return results;
        }

        long logOffset = Files.size(logFile);
        long t0 = System.nanoTime();

        List<SmokeResult> triggerResults = IoInputUdpSmokeMain.run(root);
        results.addAll(triggerResults);
        if (SmokeSupport.countFailures(triggerResults) > 0) {
            return results;
        }

        long observedSeq = -1L;
        boolean group0 = false;
        boolean group1 = false;
        long deadline = System.nanoTime() + Duration.ofMillis(slaMs + 500L).toNanos();

        while (System.nanoTime() < deadline) {
            String tail = readTailFromOffset(logFile, logOffset);
            Matcher matcher = BUCKET_COMPLETE.matcher(tail);
            while (matcher.find()) {
                long seq = Long.parseLong(matcher.group(1));
                int group = Integer.parseInt(matcher.group(2));
                if (observedSeq < 0) {
                    observedSeq = seq;
                }
                if (seq != observedSeq) {
                    continue;
                }
                if (group == 0) {
                    group0 = true;
                }
                if (group == 1) {
                    group1 = true;
                }
            }
            if (group0 && group1) {
                long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
                String detail = "seq=" + observedSeq + " elapsed_ms=" + elapsedMs + " sla_ms=" + slaMs;
                if (elapsedMs <= slaMs) {
                    results.add(SmokeResult.ok("timing", "two-buckets", detail));
                } else {
                    results.add(SmokeResult.fail("timing", "two-buckets", "SLA exceeded: " + detail));
                }
                log.info("bucket timing smoke {}", detail);
                return results;
            }
            SmokeSupport.sleep(100L);
        }

        results.add(SmokeResult.fail(
                "timing",
                "two-buckets",
                "timeout waiting for bucket complete group=0,1 within " + slaMs + " ms (seq=" + observedSeq + ")"
        ));
        return results;
    }

    private static boolean orchestratorReachable() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8099/health"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readTailFromOffset(Path logFile, long offset) throws Exception {
        long size = Files.size(logFile);
        if (size <= offset) {
            return "";
        }
        int length = (int) Math.min(size - offset, Integer.MAX_VALUE);
        byte[] bytes = new byte[length];
        try (var channel = Files.newByteChannel(logFile)) {
            channel.position(offset);
            int read = channel.read(java.nio.ByteBuffer.wrap(bytes));
            if (read <= 0) {
                return "";
            }
            return new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
