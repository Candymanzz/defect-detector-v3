package com.example.iml.orchestrator.integration.lighting.nativeio;

import org.apache.logging.log4j.LogManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Persistent MV-LE sessions, bounded FIFO per device, parallel bank operations. */
public final class NativeLightBank implements AutoCloseable {
    private final NativeLightConfig config;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService health;
    private final org.apache.logging.log4j.Logger log = LogManager.getLogger(NativeLightBank.class);
    private volatile boolean closed;
    public NativeLightBank(NativeLightConfig config) { this(config, target -> new JnaLightDevice(config, target)); }
    public NativeLightBank(NativeLightConfig config, Function<NativeLightConfig.Device, LightDevice> factory) {
        this.config = config;
        config.devices().forEach(target -> sessions.put(target.id(), new Session(target, factory)));
        health = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "light-health"); t.setDaemon(true); return t; });
        health.scheduleWithFixedDelay(this::probe, 0, config.runtime().healthCheckMs(), TimeUnit.MILLISECONDS);
    }
    private static final class RetryPending extends IllegalStateException {
        RetryPending(String message) { super(message); }
    }
    private record Job(Consumer<Session> action, CompletableFuture<Void> result, long deadline) { }
    private final class Session {
        final NativeLightConfig.Device target;
        final Function<NativeLightConfig.Device, LightDevice> factory;
        final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(config.queueSize());
        final Thread thread;
        final Map<Integer, Integer> brightness = new LinkedHashMap<>();
        final Set<Integer> refreshOn = new HashSet<>();
        LightDevice device;
        volatile boolean ready;
        volatile String error = "not connected";
        long nextConnect;
        Session(NativeLightConfig.Device target, Function<NativeLightConfig.Device, LightDevice> factory) {
            this.target = target; this.factory = factory;
            target.channels().forEach(ch -> brightness.put(ch, config.runtime().initialBrightness()));
            thread = new Thread(this::run, "light-" + target.id()); thread.setDaemon(true); thread.start();
        }
        CompletableFuture<Void> submit(Consumer<Session> action) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            if (closed || !queue.offer(new Job(action, result, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.commandTimeoutMs()))))
                result.completeExceptionally(new IllegalStateException("Light queue full or stopped: " + target.id()));
            return result;
        }
        void connect() {
            if (device != null) return;
            if (System.nanoTime() < nextConnect) throw new RetryPending("Light reconnect pending: " + target.id() + ": " + error);
            try {
                device = factory.apply(target);
                device.open();
                // Reconnection starts dark. Brightness persists; stale On commands are never replayed.
                for (int channel : target.channels()) device.source(channel, false);
                for (var entry : brightness.entrySet()) device.brightness(entry.getKey(), entry.getValue());
                ready = true; error = "";
                log.info("light event=ready device={} address={}", target.id(), target.address());
            } catch (RuntimeException | LinkageError e) { disconnect(e); throw e; }
        }
        void activate(List<Integer> channels) {
            for (int channel : channels) if (config.runtime().refreshBeforeOn() && refreshOn.contains(channel)) {
                device.source(channel, false);
                device.brightness(channel, brightness.get(channel));
            }
            if (!config.runtime().refreshBeforeOn()) for (int channel : channels) if (refreshOn.contains(channel))
                device.brightness(channel, brightness.get(channel));
            device.sources(channels, true);
            refreshOn.removeAll(channels);
        }
        void disconnect(Throwable e) {
            ready = false; error = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
            if (device != null) {
                for (int channel : target.channels()) try { device.source(channel, false); } catch (RuntimeException | LinkageError ignored) { }
                try { device.close(); } catch (RuntimeException | LinkageError ignored) { } device = null;
            }
            nextConnect = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.reconnectMs());
            log.error("light event=disconnected device={} reason={}", target.id(), error);
        }
        void run() {
            try {
                while (!closed) {
                    Job job;
                    try { job = queue.poll(config.runtime().workerPollMs(), TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { if (closed) break; else continue; }
                    if (job == null) continue;
                    if (System.nanoTime() > job.deadline()) { job.result().completeExceptionally(new TimeoutException("Expired light command")); continue; }
                    try { job.action().accept(this); job.result().complete(null); }
                    catch (RuntimeException | LinkageError e) {
                        // Local input errors do not tear down a healthy device.
                        if (!(e instanceof IllegalArgumentException) && !(e instanceof RetryPending) && device != null) disconnect(e);
                        job.result().completeExceptionally(e);
                    }
                }
            } finally {
                Job pending; while ((pending = queue.poll()) != null) pending.result().completeExceptionally(new IllegalStateException("Lights stopped"));
                if (device != null) {
                    try { for (int channel : target.channels()) try { device.source(channel, false); } catch (RuntimeException | LinkageError e) { log.error("light shutdown Off failed device={} channel={}", target.id(), channel, e); } }
                    finally { try { device.close(); } finally { device = null; ready = false; } }
                }
            }
        }
    }
    private void probe() {
        if (closed) return;
        for (Session session : sessions.values()) {
            if (!session.queue.isEmpty()) continue;
            session.submit(s -> { s.connect(); s.device.checkConnection(); });
        }
    }
    public boolean ready() { return !closed && sessions.values().stream().allMatch(s -> s.ready); }
    public Map<String, Object> status() {
        Map<String, String> errors = new LinkedHashMap<>();
        sessions.values().forEach(s -> { if (!s.ready) errors.put(s.target.id(), s.error); });
        return Map.of("initialized", ready(), "ready", sessions.values().stream().filter(s -> s.ready).count(),
                "transport", "native-mvs", "errors", errors, "configured", sessions.size());
    }
    public Map<String, Object> devices(String type) {
        List<Map<String, Object>> devices = new ArrayList<>(); int index = 0;
        for (Session s : sessions.values()) if (s.target.type().equals(type)) {
            devices.add(Map.of("index", index++, "id", s.target.id(), "modelName", String.join(",", s.target.modelPrefixes()), "tLayerType", type.equals("com") ? "MvCameraLinkDevice" : "MvGigEDevice",
                    "ipAddress", type.equals("ethernet") ? s.target.address() : "", "comPort", type.equals("com") ? s.target.address() : "",
                    "serialNumber", s.target.serial(), "connected", s.ready, "error", s.error));
        }
        return Map.of("success", true, "devices", devices, "count", devices.size());
    }
    public void cameraBrightness(int number, int... values) {
        var route = config.routes().get(number);
        if (route == null || values.length < 1 || values.length > route.channels().size()) throw new IllegalArgumentException("Invalid light camera route/powers " + number);
        Map<Integer, Integer> update = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) { validatePower(values[i]); update.put(route.channels().get(i), values[i]); }
        Session session = sessions.get(route.deviceId());
        await(List.of(session.submit(s -> {
            // Keep requested brightness across a disconnection and restore it on the next open.
            s.brightness.putAll(update); s.refreshOn.addAll(update.keySet()); s.connect();
            update.forEach((ch, power) -> s.device.brightness(ch, power));
        })));
    }
    public void cameraOn(int cameraNumber) {
        var route = config.routes().get(cameraNumber);
        if (route == null) throw new IllegalArgumentException("Unknown light camera " + cameraNumber);
        await(List.of(sessions.get(route.deviceId()).submit(s -> { s.connect(); s.activate(route.channels()); })));
    }
    public void bank(boolean on) { bank(on, null); }
    private void bank(boolean on, String type) { bank(on, type, Map.of()); }
    private void bank(boolean on, String type, Map<String, List<Integer>> powers) {
        List<CompletableFuture<Void>> results = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (type != null && !session.target.type().equals(type)) continue;
            results.add(session.submit(s -> {
                if (on) s.connect();
                if (on && powers.containsKey(s.target.id())) {
                    List<Integer> values = powers.get(s.target.id());
                    for (int i = 0; i < values.size(); i++) {
                        int channel = s.target.channels().get(i);
                        s.brightness.put(channel, values.get(i)); s.refreshOn.add(channel);
                    }
                }
                if (s.device != null) {
                    if (on) s.activate(s.target.channels());
                    else s.device.sources(s.target.channels(), false);
                }
            }));
        }
        await(results);
    }
    private void await(List<CompletableFuture<Void>> results) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.commandTimeoutMs());
        List<String> errors = new ArrayList<>();
        for (var result : results) try { result.get(Math.max(1, deadline-System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = e.getCause() == null ? e : e.getCause(); errors.add(Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()));
        }
        if (!errors.isEmpty()) throw new IllegalStateException("Light command failed: " + String.join("; ", errors));
    }
    private static void validatePower(int power) { if (power < 0 || power > 255) throw new IllegalArgumentException("Light power 0..255"); }
    public Object request(String method, String path, Map<String, Object> body) {
        path = path.split("\\?", 2)[0];
        if (method.equals("GET")) return switch (path) {
            case "/api/camera-flash/bank", "/api/com/light" -> status();
            case "/api/devices" -> devices("ethernet");
            case "/api/com/devices" -> devices("com");
            case "/api/camera-flash/routes" -> Map.of("routes", config.routes().values());
            default -> throw new IllegalArgumentException("Unknown native light route " + path);
        };
        if (!method.equals("POST")) throw new IllegalArgumentException("Unsupported light method");
        switch (path) {
            case "/api/camera-flash/pair" -> cameraBrightness(number(body, "cameraNumber"), number(body, "leftPower"), number(body, "rightPower"));
            case "/api/camera-flash/single" -> cameraBrightness(number(body, "cameraNumber"), number(body, "power"));
            case "/api/camera-flash/bank", "/api/com/light", "/api/light" -> {
                String state = Objects.toString(body.get("state"), Objects.toString(body.get("lightControllerSource"), ""));
                boolean on;
                if (state.equalsIgnoreCase("on") || state.equals("1")) on = true;
                else if (state.equalsIgnoreCase("off") || state.equals("0") || state.equals("255")) on = false;
                else throw new IllegalArgumentException("Light state must be on/off");
                String type = path.equals("/api/com/light") ? "com" : path.equals("/api/light") ? "ethernet" : null;
                // Legacy requests with explicit target/channel selection must not accidentally drive the whole bank.
                if (body.containsKey("deviceIndex") || body.containsKey("comPort") || body.containsKey("ipAddress") || body.containsKey("serialNumber") || body.containsKey("channels")) {
                    targetCommand(type, body, on);
                } else bank(on, type, bankPowers(type, body));
            }
            default -> throw new IllegalArgumentException("Unknown native light route " + path);
        }
        return Map.of("success", true, "transport", "native-mvs");
    }
    private Map<String, List<Integer>> bankPowers(String type, Map<String, Object> body) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        if (body.get("brightnessByIp") instanceof Map<?, ?> byIp) for (var entry : byIp.entrySet()) {
            Session session = sessions.values().stream().filter(s -> s.target.type().equals("ethernet")
                    && s.target.address().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unconfigured brightness IP " + entry.getKey()));
            if (!(entry.getValue() instanceof List<?> list)) throw new IllegalArgumentException("Brightness must be an array");
            List<Integer> values = list.stream().map(v -> Integer.parseInt(v.toString())).toList();
            if (values.size() != session.target.channels().size()) throw new IllegalArgumentException("Brightness/channel count mismatch");
            values.forEach(NativeLightBank::validatePower); result.put(session.target.id(), values);
        }
        if (body.containsKey("brightness")) {
            if (!"com".equals(type) || !(body.get("brightness") instanceof String csv))
                throw new IllegalArgumentException("Bank brightness uses COM percent CSV or brightnessByIp");
            List<Session> com = sessions.values().stream().filter(s -> s.target.type().equals("com")).toList();
            int count = com.stream().mapToInt(s -> s.target.channels().size()).sum();
            List<Integer> percent = Arrays.stream(csv.split(",", -1)).map(String::trim).map(Integer::parseInt).toList();
            if ((percent.size() != 1 && percent.size() != count) || percent.stream().anyMatch(v -> v < 0 || v > 100))
                throw new IllegalArgumentException("COM brightness: one percent or one per configured channel, 0..100");
            int index = 0;
            for (Session session : com) {
                List<Integer> values = new ArrayList<>();
                for (int channel : session.target.channels()) values.add(Math.round(percent.get(percent.size() == 1 ? 0 : index++) * 255f / 100f));
                result.put(session.target.id(), values);
            }
        }
        return result;
    }
    private void targetCommand(String type, Map<String, Object> body, boolean on) {
        List<Session> targets = sessions.values().stream().filter(s -> type == null || s.target.type().equals(type)).toList();
        Session target;
        if (body.get("comPort") != null) {
            String port = NativeLightConfig.sdkPort(body.get("comPort").toString());
            target = targets.stream().filter(s -> s.target.address().equals(port)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown COM light port"));
        } else if (body.get("ipAddress") != null || body.get("serialNumber") != null) {
            target = targets.stream().filter(s -> (body.get("ipAddress") == null || s.target.address().equals(body.get("ipAddress")))
                    && (body.get("serialNumber") == null || s.target.serial().equals(body.get("serialNumber"))))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown configured light IP/serial"));
        } else {
            int index = number(body, "deviceIndex");
            if (index < 0 || index >= targets.size()) throw new IllegalArgumentException("Unknown light index"); target = targets.get(index);
        }
        List<Integer> channels = body.get("channels") instanceof List<?> list ? list.stream().map(v -> Integer.parseInt(v.toString())).toList() : target.target.channels();
        if (channels.isEmpty() || !target.target.channels().containsAll(channels)) throw new IllegalArgumentException("Unconfigured light channel");
        List<Integer> powers = body.get("brightness") instanceof List<?> list ? list.stream().map(v -> Integer.parseInt(v.toString())).toList() : List.of();
        if (!powers.isEmpty() && powers.size() != channels.size()) throw new IllegalArgumentException("Brightness/channel count mismatch");
        powers.forEach(NativeLightBank::validatePower);
        await(List.of(target.submit(s -> {
            if (on) s.connect();
            if (s.device == null) return;
            for (int i = 0; i < channels.size(); i++) {
                int channel = channels.get(i);
                if (on && !powers.isEmpty()) { s.brightness.put(channel, powers.get(i)); s.device.brightness(channel, powers.get(i)); }
                s.device.source(channel, on);
            }
        })));
    }
    private static int number(Map<String, Object> map, String key) {
        if (map.get(key) == null) throw new IllegalArgumentException("Missing " + key); return Integer.parseInt(map.get(key).toString());
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; health.shutdownNow(); sessions.values().forEach(s -> s.thread.interrupt());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownTimeoutMs());
        for (Session session : sessions.values()) try {
            session.thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
            if (session.thread.isAlive()) log.error("light event=shutdown_timeout device={} native_call_still_running=true", session.target.id());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("light event=bank_closed");
    }
}
