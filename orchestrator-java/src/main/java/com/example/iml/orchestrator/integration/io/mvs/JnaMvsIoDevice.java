package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import org.apache.logging.log4j.Logger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** MVS C ABI, matching CameraParams.h (64-bit SDK). No output routing writes. */
public final class JnaMvsIoDevice implements MvsIoDevice {
    @Structure.FieldOrder({"count", "devices"})
    public static class DeviceList extends Structure {
        public int count;
        public Pointer[] devices = new Pointer[256];
    }
    @Structure.FieldOrder({"current", "count", "supported", "reserved"})
    public static class EnumValue extends Structure {
        public int current, count;
        public int[] supported = new int[64], reserved = new int[4];
    }
    @Structure.FieldOrder({"value", "symbol", "reserved"})
    public static class EnumEntry extends Structure {
        public int value;
        public byte[] symbol = new byte[64];
        public int[] reserved = new int[4];
    }
    public interface EventCallback extends com.sun.jna.win32.StdCallLibrary.StdCallCallback { void invoke(Pointer event, Pointer user); }
    private final MvsIoConfig config;
    private final Logger log;
    private NativeLibrary lib;
    private MvsSdkLease sdkLease;
    private Pointer handle;
    private boolean initialized, opened;
    private String originalLine, originalEventSelector;
    // Strong references must survive until CloseDevice has stopped SDK callbacks.
    private final List<EventCallback> callbacks = new ArrayList<>();
    private final Map<String, String> originalNotifications = new LinkedHashMap<>();

    public JnaMvsIoDevice(MvsIoConfig config, Logger log) { this.config = config; this.log = log; }

    @Override public synchronized void open() { open(true); }

    public synchronized void open(boolean validateInputs) {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("MVS IO requires a 64-bit JVM");
        try {
            lib = MvsNativeLibrary.load(config.library());
            sdkLease = MvsSdkLease.acquire(MvsNativeLibrary.key(lib), () -> call("MV_CC_Initialize"), () -> call("MV_CC_Finalize")); initialized = true;
            synchronized (MvsSdkLease.ENUMERATION_LOCK) {
            List<Pointer> matches = enumerate();
            if (matches.size() != 1) throw new IllegalStateException("Expected one MV-VC controller, found " + matches.size()
                    + "; set mvs_io.port/serial (diagnose with MvsIoDiagnosticMain --list)");
            PointerByReference ref = new PointerByReference();
            call("MV_CC_CreateHandle", ref, matches.get(0)); handle = ref.getValue();
            call("MV_CC_OpenDevice", handle, 1, (short) 0); opened = true;
            }
            originalLine = current("LineSelector");
            if (validateInputs) for (MvsIoConfig.Input input : config.inputs().values()) {
                setEnum("LineSelector", input.line());
                bool("LineStatus"); // Fail immediately if the specified DI is not readable.
            }
            if (validateInputs) for (String output : config.outputs().values()) {
                setEnum("LineSelector", output);
                bool("LineStatus");
            }
            setEnum("LineSelector", originalLine);
            log.info("MVS IO monitor connected inputs={} outputs={} input_mode={}",
                    config.inputs().keySet(), config.outputs(), config.inputMode());
        } catch (RuntimeException | LinkageError e) { close(); throw e; }
    }

    /** Enumeration only: never opens a device or executes an output command. */
    public synchronized int listDevices() {
        lib = MvsNativeLibrary.load(config.library());
        sdkLease = MvsSdkLease.acquire(MvsNativeLibrary.key(lib), () -> call("MV_CC_Initialize"), () -> call("MV_CC_Finalize")); initialized = true;
        return enumerate().size();
    }

    private List<Pointer> enumerate() {
        synchronized (MvsSdkLease.ENUMERATION_LOCK) { return enumerateLocked(); }
    }
    private List<Pointer> enumerateLocked() {
        if (!config.port().isEmpty()) {
            byte[] port = config.port().getBytes(StandardCharsets.UTF_8);
            if (port.length >= 64) throw new IllegalArgumentException("MVS port name exceeds 63 bytes");
            // MV_CAML_SERIAL_PORT_LIST: count + 64*(char[64]+uint[4]) + uint[4].
            try (Memory ports = new Memory(5140)) {
                ports.clear(); ports.setInt(0, 1); ports.write(4, port, 0, port.length);
                call("MV_CAML_SetEnumSerialPorts", ports);
            }
        }
        DeviceList list = new DeviceList();
        call("MV_CC_EnumDevices", 8, list); list.read();
        if (list.count < 0 || list.count > list.devices.length) throw new IllegalStateException("Invalid MVS device count");
        List<Pointer> matches = new ArrayList<>();
        for (int i = 0; i < list.count; i++) {
            Pointer info = list.devices[i];
            if (info == null || info.getInt(12) != 8) continue;
            // MV_CC_DEVICE_INFO.SpecialInfo at byte 32; MV_CamL_DEV_INFO char[64] fields.
            String port = cString(info.getByteArray(32, 64));
            String model = cString(info.getByteArray(96, 64));
            String serial = cString(info.getByteArray(352, 64));
            log.info("MVS serial device port={} model={} serial={}", port, model, serial);
            if (model.startsWith("MV-VC") && (config.port().isEmpty() || config.port().equals(port))
                    && (config.serial().isEmpty() || config.serial().equals(serial))) matches.add(info);
        }
        return matches;
    }

    @Override public synchronized Map<Integer, Boolean> readInputs() {
        ensureOpen();
        Map<Integer, Boolean> states = new TreeMap<>();
        for (var entry : new TreeMap<>(config.inputs()).entrySet()) {
            // Symbols/readability were checked on open. Avoid extra serial round trips per sample.
            // Close restores the original selector; no timer or output values are written.
            call("MV_CC_SetEnumValueByString", handle, "LineSelector", entry.getValue().line());
            states.put(entry.getKey(), bool("LineStatus") ^ entry.getValue().invert());
        }
        return states;
    }

    @Override public synchronized void triggerTimer(String timer, String line) {
        ensureOpen();
        MvsTimerCommand.trigger(timer, line, new MvsTimerCommand.Nodes() {
            public String get(String node) { return current(node); }
            public void select(String node, String value) { setEnum(node, value); }
            public int access(String node) {
                com.sun.jna.ptr.IntByReference access = new com.sun.jna.ptr.IntByReference();
                call("MV_XML_GetNodeAccessMode", handle, node, access); return access.getValue();
            }
            public void command(String node) { call("MV_CC_SetCommandValue", handle, node); }
            public void restore(String node, String value) { cleanup(() -> setEnum(node, value)); }
        });
        log.info("io event=timer_trigger_accepted timer={} line={}", timer, line);
    }

    @Override public synchronized Map<Integer, Boolean> readOutputs() {
        ensureOpen();
        Map<Integer, Boolean> states = new TreeMap<>();
        for (var entry : new TreeMap<>(config.outputs()).entrySet()) {
            call("MV_CC_SetEnumValueByString", handle, "LineSelector", entry.getValue());
            states.put(entry.getKey(), bool("LineStatus"));
        }
        return states;
    }

    @Override public synchronized void subscribe(Consumer<IoInputDiChange> listener) {
        ensureOpen();
        List<String> supported = symbols("EventSelector");
        // Resolve every binding before enabling any event.
        Map<String, IoInputDiChange> bindings = resolveEvents(config.inputs(), supported);
        String oldSelector = current("EventSelector");
        originalEventSelector = oldSelector;
        try {
            for (var binding : bindings.entrySet()) {
                String eventName = binding.getKey();
                EventCallback callback = (event, user) -> listener.accept(binding.getValue());
                callbacks.add(callback);
                call("MV_CC_RegisterEventCallBackEx", handle, eventName, callback, null);
                setEnum("EventSelector", eventName);
                originalNotifications.put(eventName, current("EventNotification"));
                setEnum("EventNotification", "On");
            }
        } finally { setEnum("EventSelector", oldSelector); }
    }

    /** Auto-discovery only accepts an exact configured line plus a named edge. No Line0/DI1 guessing. */
    public static Map<String, IoInputDiChange> resolveEvents(Map<Integer, MvsIoConfig.Input> inputs, List<String> supported) {
        Map<String, IoInputDiChange> result = new LinkedHashMap<>();
        for (var entry : new TreeMap<>(inputs).entrySet()) {
            for (boolean rising : new boolean[]{true, false}) {
                MvsIoConfig.Input input = entry.getValue();
                String configured = rising ? input.risingEvent() : input.fallingEvent();
                List<String> candidates = configured.isEmpty()
                        ? supported.stream().filter(s -> normalize(s).equals(normalize(input.line() + (rising ? "RisingEdge" : "FallingEdge")))
                            || normalize(s).equals(normalize(input.line() + (rising ? "Rising" : "Falling")))).toList()
                        : supported.stream().filter(configured::equals).toList();
                if (candidates.size() != 1) throw new IllegalStateException("Specify mvs_io.inputs." + entry.getKey()
                        + (rising ? ".rising_event" : ".falling_event") + "; supported EventSelector=" + supported);
                if (result.put(candidates.get(0), new IoInputDiChange(entry.getKey(), rising ^ input.invert())) != null)
                    throw new IllegalArgumentException("MVS event assigned to multiple DI edges: " + candidates.get(0));
            }
        }
        return result;
    }

    private static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private boolean bool(String node) {
        try (Memory value = new Memory(1)) { call("MV_CC_GetBoolValue", handle, node, value); return value.getByte(0) != 0; }
    }
    private EnumValue enumValue(String node) {
        EnumValue value = new EnumValue(); call("MV_CC_GetEnumValue", handle, node, value); value.read();
        if (value.count < 0 || value.count > 64) throw new IllegalStateException("Invalid enum count for " + node);
        return value;
    }
    private String symbol(String node, int value) {
        EnumEntry entry = new EnumEntry(); entry.value = value; entry.write();
        call("MV_CC_GetEnumEntrySymbolic", handle, node, entry); entry.read(); return cString(entry.symbol);
    }
    private String current(String node) { return symbol(node, enumValue(node).current); }
    private List<String> symbols(String node) {
        EnumValue values = enumValue(node); List<String> result = new ArrayList<>();
        for (int i = 0; i < values.count; i++) result.add(symbol(node, values.supported[i]));
        return result;
    }
    private void setEnum(String node, String value) {
        call("MV_CC_SetEnumValueByString", handle, node, value);
        if (!current(node).equals(value)) throw new IllegalStateException("MVS readback mismatch for " + node);
    }
    private void ensureOpen() { if (!opened) throw new IllegalStateException("MVS controller is disconnected"); }
    private void call(String name, Object... args) {
        long started = System.nanoTime();
        int code = lib.getFunction(name, Platform.isWindows() ? Function.ALT_CONVENTION : Function.C_CONVENTION).invokeInt(args);
        log.debug("io event=sdk_call operation={} result={} duration_ms={} port={}",
                name, String.format("0x%08x", code), (System.nanoTime() - started) / 1_000_000.0, config.port());
        if (code != 0) throw new IllegalStateException(name + ": MVS error " + String.format("0x%08x", code));
    }
    private static String cString(byte[] bytes) {
        int length = 0; while (length < bytes.length && bytes[length] != 0) length++;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    public synchronized byte[] xml() {
        ensureOpen();
        try (Memory data = new Memory(16 * 1024 * 1024)) {
            com.sun.jna.ptr.IntByReference length = new com.sun.jna.ptr.IntByReference();
            call("MV_XML_GetGenICamXML", handle, data, (int) data.size(), length);
            if (length.getValue() < 0 || length.getValue() > data.size()) throw new IllegalStateException("Invalid XML size");
            return data.getByteArray(0, length.getValue());
        }
    }

    public synchronized Map<String, Object> inspect() {
        ensureOpen(); Map<String, Object> result = new LinkedHashMap<>();
        try { result.put("inputs", readInputs()); }
        catch (RuntimeException e) { result.put("inputs_error", e.getMessage()); }
        result.put("LineSelector", symbols("LineSelector"));
        try { result.put("EventSelector", symbols("EventSelector")); }
        catch (RuntimeException e) { result.put("EventSelector_error", e.getMessage()); }
        result.put("outputs", readOutputs()); return result;
    }

    @Override public synchronized void close() {
        log.info("io event=device_close_requested backend=mv_camera port={} opened={}", config.port(), opened);
        if (opened) {
            for (var entry : originalNotifications.entrySet()) {
                cleanup(() -> { setEnum("EventSelector", entry.getKey()); setEnum("EventNotification", entry.getValue()); });
            }
            if (originalEventSelector != null) cleanup(() -> setEnum("EventSelector", originalEventSelector));
            if (originalLine != null) cleanup(() -> setEnum("LineSelector", originalLine));
            cleanup(() -> call("MV_CC_CloseDevice", handle)); opened = false;
        }
        if (handle != null) { cleanup(() -> call("MV_CC_DestroyHandle", handle)); handle = null; }
        if (initialized) { cleanup(sdkLease::close); sdkLease = null; initialized = false; }
        callbacks.clear(); originalNotifications.clear();
        log.info("io event=device_closed backend=mv_camera port={}", config.port());
        // Do not unload the process-wide library while another MVS user might still use it.
    }
    private void cleanup(Runnable operation) {
        try { operation.run(); } catch (RuntimeException | LinkageError e) { log.warn("MVS IO cleanup: {}", e.getMessage()); }
    }
}
