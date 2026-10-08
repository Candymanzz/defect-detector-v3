package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import org.apache.logging.log4j.Logger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Direct DI callbacks and explicitly configured reject DO levels; camera routing is untouched. */
public final class JnaMvIoDevice implements MvsIoDevice {
    @Structure.FieldOrder({"comName", "reserved"})
    public static class Serial extends Structure {
        public byte[] comName = new byte[64]; public int[] reserved = new int[8];
    }
    @Structure.FieldOrder({"port", "enable", "edge", "delay", "glitch", "reserved"})
    public static class InputParam extends Structure {
        public int port, enable, edge, delay, glitch; public int[] reserved = new int[8];
    }
    @Structure.FieldOrder({"mask", "levels", "reserved"})
    public static class Levels extends Structure {
        public byte mask; public byte[] levels = new byte[8]; public int[] reserved = new int[8];
    }
    @Structure.FieldOrder({"port", "times", "edge", "reserved"})
    public static class Edge extends Structure {
        public byte port; public short times; public int edge; public int[] reserved = new int[8];
        public Edge() { }
        public Edge(Pointer pointer) { super(pointer); read(); }
    }
    @Structure.FieldOrder({"main", "sub", "modify", "year", "month", "day", "reserved"})
    public static class Version extends Structure {
        public int main, sub, modify, year, month, day; public int[] reserved = new int[8];
    }
    @Structure.FieldOrder({"port", "status", "reserved"})
    public static class OutputLevel extends Structure {
        public int port, status; public int[] reserved = new int[8];
    }
    @Structure.FieldOrder({"port", "enable", "reserved"})
    public static class OutputEnable extends Structure {
        public int port, enable; public int[] reserved = new int[8];
    }
    private final Set<Integer> initializedOutputs = new HashSet<>();
    public interface EdgeCallback extends Callback { void invoke(Pointer handle, Pointer edge, Pointer user); }
    public interface WindowsEdgeCallback extends EdgeCallback, com.sun.jna.win32.StdCallLibrary.StdCallCallback { }
    @FunctionalInterface interface Calls { int invoke(String name, Object... args); }
    private record Notification(long id, int mask, int edge, int triggerTimes, long receivedNs) { }
    private final java.util.concurrent.atomic.AtomicLong callbackIds = new java.util.concurrent.atomic.AtomicLong();
    private final MvsIoConfig config;
    private final Logger log;
    private Calls sdk;
    private Pointer handle;
    private boolean opened;
    private volatile boolean listening;
    private volatile RuntimeException eventFailure;
    private EdgeCallback callback; // retain until Close has stopped native callbacks
    private Thread eventThread;
    private final BlockingQueue<Notification> pending = new LinkedBlockingQueue<>();
    private final Map<Integer, InputParam> originals = new LinkedHashMap<>();
    private final List<NativeLibrary> dependencies = new ArrayList<>();
    private final Map<Integer, Integer> logicalPorts = new HashMap<>();
    private Map<Integer, Boolean> initial = Map.of();

    public JnaMvIoDevice(MvsIoConfig config, Logger log) { this(config, log, null); }
    JnaMvIoDevice(MvsIoConfig config, Logger log, Calls sdk) {
        this.config = config; this.log = log; this.sdk = sdk;
        for (var entry : config.inputs().entrySet()) {
            int physical = physicalPort(entry.getKey(), entry.getValue());
            if (logicalPorts.put(physical, entry.getKey()) != null)
                throw new IllegalArgumentException("Duplicate physical DI port " + physical);
        }
    }
    private static int physicalPort(int logical, MvsIoConfig.Input input) { return input.port() == 0 ? logical : input.port(); }
    private void call(String name, Object... args) {
        long started = System.nanoTime();
        int result = sdk.invoke(name, args);
        log.debug("io event=sdk_call operation={} result={} duration_ms={} port={}",
                name, String.format("0x%08x", result), (System.nanoTime() - started) / 1_000_000.0, config.port());
        if (result != 0) throw new IllegalStateException(name + ": IO SDK error " + String.format("0x%08x", result));
    }
    @Override public synchronized void open() {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("IO SDK requires a 64-bit JVM");
        if (!config.serial().isEmpty()) throw new IllegalArgumentException("mv_io uses an explicit port; serial filtering is unavailable");
        if (!config.outputs().isEmpty()) throw new IllegalArgumentException(
                "mv_io SDK has no verified DO level read API; set outputs: {}. Output profile Level is not a measured DO level");
        try {
            if (sdk == null) {
                for (String dependency : config.dependencies()) {
                    log.info("io event=load_dependency library={}", dependency);
                    dependencies.add(NativeLibrary.getInstance(dependency));
                }
                log.info("io event=load_library library={} os={}", config.library(), System.getProperty("os.name"));
                Map<String, Object> options = Platform.isWindows()
                        ? Map.of(Library.OPTION_OPEN_FLAGS, 0x1100) : Map.of();
                NativeLibrary library = NativeLibrary.getInstance(java.nio.file.Path.of(config.library()).toAbsolutePath().normalize().toString(), options);
                sdk = (name, args) -> {
                    Function function = library.getFunction(name, Platform.isWindows() ? Function.ALT_CONVENTION : Function.C_CONVENTION);
                    if (name.equals("MV_IO_Close")) { function.invokeVoid(args); return 0; }
                    return function.invokeInt(args);
                };
            }
            PointerByReference result = new PointerByReference();
            call("MV_IO_CreateHandle", result); handle = result.getValue();
            if (handle == null) throw new IllegalStateException("IO SDK returned a null handle");
            byte[] name = config.port().getBytes(StandardCharsets.UTF_8);
            if (name.length == 0 || name.length >= 64) throw new IllegalArgumentException("IO port must contain 1..63 UTF-8 bytes");
            Serial serial = new Serial(); System.arraycopy(name, 0, serial.comName, 0, name.length);
            call("MV_IO_Open", handle, serial); opened = true;
            initial = readInputs();
            log.info("io event=initial_inputs port={} values={}", config.port(), initial);
            log.info("MVS IO event backend opened port={} inputs={}", config.port(), config.inputs().keySet());
        } catch (RuntimeException | LinkageError e) { close(); throw e; }
    }
    @Override public synchronized Map<Integer, Boolean> readInputs() {
        ensureOpen();
        // This API may reject reads after edge detection is enabled. Keep the callback snapshot.
        if (listening) return initial;
        Levels levels = new Levels();
        for (int physical : logicalPorts.keySet()) levels.mask |= (byte)(1 << (physical - 1));
        call("MV_IO_GetInputLevel", handle, levels); levels.read();
        Map<Integer, Boolean> states = new TreeMap<>();
        logicalPorts.forEach((physical, logical) -> states.put(logical,
                (levels.levels[physical - 1] != 0) ^ config.inputs().get(logical).invert()));
        return Map.copyOf(states);
    }
    @Override public synchronized void setOutput(int port, boolean high) {
        ensureOpen();
        IoRejectConfig.Output output = config.reject().groups().values().stream()
                .filter(value -> value.port() == port).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DO is not configured for reject: " + port));
        if (!config.reject().enabled()) throw new IllegalStateException("DO rejects are disabled");
        log.info("io event=do_write_requested do={} sdk_port={} physical_high={}", port, output.sdkPort(), high);
        if (!initializedOutputs.contains(port)) {
            if (output.enableOnWrite()) {
                OutputEnable enable = new OutputEnable(); enable.port = output.sdkPort(); enable.enable = 0; // SDK Start
                call("MV_IO_SetOutputEnable", handle, enable);
            }
            initializedOutputs.add(port);
        }
        OutputLevel level = new OutputLevel(); level.port = output.sdkPort(); level.status = high ? 1 : 0;
        call("MV_IO_SetMainOutputLevel", handle, level);
        log.info("io event=do_write do={} sdk_port={} physical_high={}", port, output.sdkPort(), high);
    }
    @Override public Map<Integer, Boolean> readOutputs() { return Map.of(); }
    @Override public synchronized void checkConnection() {
        ensureOpen();
        if (eventFailure != null) throw eventFailure;
        call("MV_IO_GetFirmwareVersion", handle, new Version());
    }
    @Override public synchronized void subscribe(Consumer<IoInputDiChange> receiver) {
        ensureOpen();
        if (listening) throw new IllegalStateException("IO callback already registered");
        eventFailure = null; listening = true;
        EdgeCallback handler = (ignored, pointer, user) -> {
            if (!listening || pointer == null) return;
            try {
                Edge edge = new Edge(pointer);
                int mask = Byte.toUnsignedInt(edge.port);
                pending.offer(new Notification(callbackIds.incrementAndGet(), mask, edge.edge,
                        Short.toUnsignedInt(edge.times), System.nanoTime()));
            } catch (RuntimeException e) { eventFailure = e; }
        };
        callback = Platform.isWindows() ? (WindowsEdgeCallback) handler::invoke : handler;
        try {
            call("MV_IO_RegisterEdgeDetectionCallBack", handle, callback, null);
            log.info("io event=callback_registered port={} configure_inputs={}", config.port(), config.configureInputs());
            for (var entry : config.inputs().entrySet()) {
                int physical = physicalPort(entry.getKey(), entry.getValue());
                if (config.configureInputs()) {
                    InputParam original = new InputParam(); original.port = 1 << (physical - 1);
                    call("MV_IO_GetPortInputParam", handle, original); original.read(); originals.put(physical, original);
                    log.info("io event=input_settings_saved physical_di={} enable={} edge={} delay={} glitch={}",
                            physical, original.enable, original.edge, original.delay, original.glitch);
                    boolean rawHigh = initial.get(entry.getKey()) ^ entry.getValue().invert();
                    arm(physical, entry.getValue().edge(), rawHigh);
                }
            }
            eventThread = new Thread(() -> consume(receiver), "mvs-io-edge-rearm");
            eventThread.setDaemon(true); eventThread.start();
        } catch (RuntimeException | LinkageError e) { close(); throw e; }
    }
    private void consume(Consumer<IoInputDiChange> receiver) {
        while (listening) {
            try {
                Notification event = pending.take();
                log.info("io event=sdk_edge sdk_event_id={} mask={} edge={} trigger_times={} queue_wait_ms={} pending_events={}",
                        event.id(), event.mask(), event.edge(), event.triggerTimes(),
                        (System.nanoTime() - event.receivedNs()) / 1_000_000.0, pending.size());
                if (Integer.bitCount(event.mask()) != 1 || (event.edge() != 1 && event.edge() != 2)) {
                    log.warn("io event=edge_ignored sdk_event_id={} reason=invalid_mask_or_edge", event.id());
                    continue;
                }
                int physical = Integer.numberOfTrailingZeros(event.mask()) + 1;
                Integer logical = logicalPorts.get(physical);
                if (logical == null) {
                    log.info("io event=edge_ignored sdk_event_id={} physical_di={} reason=not_subscribed", event.id(), physical);
                    continue;
                }
                MvsIoConfig.Input input = config.inputs().get(logical);
                boolean rawHigh = event.edge() == 1;
                boolean selected = input.edge().equals("both")
                        || (input.edge().equals("rising") == rawHigh);
                if (selected) {
                    boolean active = rawHigh ^ input.invert();
                    synchronized (this) {
                        if (!listening) break;
                        Map<Integer, Boolean> states = new TreeMap<>(initial);
                        states.put(logical, active); initial = Map.copyOf(states);
                    }
                    log.info("io event=di_change sdk_event_id={} physical_di={} logical_di={} raw_value={} invert={} value={}",
                            event.id(), physical, logical, rawHigh ? 1 : 0, input.invert(), active ? 1 : 0);
                    receiver.accept(new IoInputDiChange(logical, active));
                }
                if (!selected) log.info("io event=edge_ignored sdk_event_id={} logical_di={} configured_edge={} reason=edge_filter",
                        event.id(), logical, input.edge());
                if (config.configureInputs()) synchronized (this) {
                    if (listening) arm(physical, input.edge(), rawHigh);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (RuntimeException e) { eventFailure = e; log.error("IO edge processing failed", e); break; }
        }
    }
    private void arm(int port, String mode, boolean rawHigh) {
        InputParam input = new InputParam(); input.port = 1 << (port - 1); input.enable = 1;
        input.edge = mode.equals("falling") || (mode.equals("both") && rawHigh) ? 2 : 1;
        // No debounce or delay. Only input event detection is configured.
        call("MV_IO_SetInput", handle, input);
        log.info("io event=input_armed physical_di={} edge={} mode={} raw_high={} delay=0 glitch=0", port,
                input.edge == 1 ? "rising" : "falling", mode, rawHigh);
    }
    private void ensureOpen() { if (!opened) throw new IllegalStateException("IO SDK is disconnected"); }
    @Override public void close() {
        log.info("io event=device_close_requested port={} opened={} pending_callbacks={}", config.port(), opened, pending.size());
        Thread thread;
        synchronized (this) { listening = false; thread = eventThread; }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try { thread.join(config.shutdownTimeoutMs()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (thread.isAlive()) { log.error("IO event thread still stopping; keeping SDK handle alive"); return; }
        }
        synchronized (this) {
            if (opened) {
                for (IoRejectConfig.Output output : config.reject().groups().values()) {
                    if (!initializedOutputs.contains(output.port())) continue;
                    OutputLevel idle = new OutputLevel(); idle.port = output.sdkPort(); idle.status = output.activeHigh() ? 0 : 1;
                    cleanup("MV_IO_SetMainOutputLevel", handle, idle);
                }
                for (var entry : originals.entrySet()) {
                    InputParam original = entry.getValue();
                    if (cleanup("MV_IO_SetInput", handle, original))
                        log.info("io event=input_settings_restored physical_di={} enable={} edge={} delay={} glitch={}",
                                entry.getKey(), original.enable, original.edge, original.delay, original.glitch);
                }
                cleanup("MV_IO_Close", handle); opened = false;
            }
            if (handle != null) { cleanup("MV_IO_DestroyHandle", handle); handle = null; }
            initializedOutputs.clear();
            originals.clear(); pending.clear(); callback = null; eventThread = null;
            log.info("io event=device_closed port={} callbacks_received={}", config.port(), callbackIds.get());
        }
    }
    private boolean cleanup(String name, Object... args) {
        try { call(name, args); return true; }
        catch (RuntimeException | LinkageError e) { log.warn("io event=cleanup_failed operation={} port={}", name, config.port(), e); return false; }
    }
}
