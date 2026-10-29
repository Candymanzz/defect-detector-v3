package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.Logger;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Bounded RPC to an expendable JVM; no native handle or stuck SDK thread in the orchestrator. */
final class ProcessMvsIoDevice implements MvsIoDevice {
    private final MvsIoConfig config;
    private final Logger log;
    private final ProcessBuilder builder;
    private final ReentrantLock commands = new ReentrantLock(true);
    private final BlockingQueue<JsonNode> responses = new LinkedBlockingQueue<>();
    private volatile Process process;
    private volatile boolean broken;
    private volatile Consumer<IoInputDiChange> listener = change -> {};
    private BufferedWriter writer;

    ProcessMvsIoDevice(MvsIoConfig config, Logger log) {
        this(config, log, new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), MvsIoWorkerMain.class.getName()));
    }
    ProcessMvsIoDevice(MvsIoConfig config, Logger log, ProcessBuilder builder) {
        this.config = config; this.log = log; this.builder = builder;
    }
    @Override public void open() {
        try {
            process = builder.redirectError(ProcessBuilder.Redirect.INHERIT).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::readResponses, "mvs-io-worker-responses");
            reader.setDaemon(true); reader.start();
            log.info("io event=sdk_worker_started pid={} timeout_ms={}", process.pid(), config.sdkTimeoutMs());
            request(Map.of("op", "open", "config", config), Math.max(15000, config.sdkTimeoutMs()));
        } catch (IOException | RuntimeException e) {
            abort();
            throw new IllegalStateException("IO SDK worker startup failed", e);
        }
    }
    private void readResponses() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int start = line.indexOf(MvsIoWorkerMain.PREFIX);
                if (start < 0) { log.debug("IO SDK stdout: {}", line); continue; }
                JsonNode value = MvsIoWorkerMain.JSON.readTree(line.substring(start + MvsIoWorkerMain.PREFIX.length()));
                if (value.has("event")) listener.accept(MvsIoWorkerMain.JSON.treeToValue(value.get("event"), IoInputDiChange.class));
                else responses.add(value);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("IO SDK worker response stream closed: {}", e.toString());
        } finally {
            responses.add(MvsIoWorkerMain.JSON.createObjectNode().put("ok", false).put("error", "IO SDK worker exited"));
        }
    }
    private JsonNode request(Map<String, ?> command) { return request(command, config.sdkTimeoutMs()); }
    private JsonNode request(Map<String, ?> command, int timeoutMs) {
        commands.lock();
        try {
            if (broken) throw new IllegalStateException("IO SDK worker disconnected");
            writer.write(MvsIoWorkerMain.JSON.writeValueAsString(command)); writer.newLine(); writer.flush();
            JsonNode response = responses.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (response == null) {
                log.error("io event=sdk_timeout op={} timeout_ms={} pid={} action=kill_and_reconnect",
                        command.get("op"), timeoutMs, process.pid());
                throw new IllegalStateException("IO SDK timeout op=" + command.get("op"));
            }
            if (!response.path("ok").asBoolean()) throw new IllegalStateException(response.path("error").asText());
            return response.get("result");
        } catch (IOException e) {
            abort(); throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            abort(); Thread.currentThread().interrupt(); throw new IllegalStateException("IO SDK request interrupted", e);
        } catch (RuntimeException e) {
            abort(); throw e;
        } finally { commands.unlock(); }
    }
    private Map<Integer, Boolean> levels(String op) {
        return MvsIoWorkerMain.JSON.convertValue(request(Map.of("op", op)),
                new com.fasterxml.jackson.core.type.TypeReference<Map<Integer, Boolean>>() {});
    }
    @Override public Map<Integer, Boolean> readInputs() { return levels("inputs"); }
    @Override public Map<Integer, Boolean> readOutputs() { return levels("outputs"); }
    @Override public void checkConnection() { request(Map.of("op", "health")); }
    @Override public void subscribe(Consumer<IoInputDiChange> listener) {
        this.listener = listener; request(Map.of("op", "subscribe"));
    }
    @Override public void setOutput(int port, boolean high) { request(Map.of("op", "set", "port", port, "high", high)); }
    @Override public void setOutputs(Map<Integer, Boolean> levels) { request(Map.of("op", "setAll", "levels", levels)); }
    @Override public void triggerTimer(String timer, String line) { request(Map.of("op", "timer", "timer", timer, "line", line)); }
    private void abort() {
        broken = true;
        Process current = process;
        if (current != null && current.isAlive()) current.destroyForcibly();
    }
    @Override public void close() {
        try {
            if (!broken && process != null && process.isAlive()) request(Map.of("op", "close"), config.shutdownTimeoutMs());
        } finally {
            abort();
            if (process != null) {
                try { if (!process.waitFor(config.shutdownTimeoutMs(), TimeUnit.MILLISECONDS))
                    log.error("io event=sdk_worker_kill_timeout pid={}", process.pid()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
