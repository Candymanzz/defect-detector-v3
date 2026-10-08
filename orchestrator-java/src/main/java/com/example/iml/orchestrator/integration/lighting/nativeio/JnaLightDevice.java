package com.example.iml.orchestrator.integration.lighting.nativeio;

import com.example.iml.orchestrator.integration.io.mvs.JnaMvsIoDevice;
import com.example.iml.orchestrator.integration.io.mvs.MvsSdkLease;
import com.example.iml.orchestrator.integration.io.mvs.MvsNativeLibrary;
import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import org.apache.logging.log4j.LogManager;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official MvCameraControl C ABI; configured MV-LE targets only, never an IO controller/camera. */
public final class JnaLightDevice implements LightDevice {
    @Structure.FieldOrder({"current", "max", "min", "increment", "reserved"})
    public static class IntValue extends Structure {
        public long current, max, min, increment;
        public int[] reserved = new int[16];
    }
    @Structure.FieldOrder({"current", "max", "min", "reserved"})
    public static class FloatValue extends Structure {
        public float current, max, min;
        public int[] reserved = new int[4];
    }
    private final NativeLightConfig config;
    private final NativeLightConfig.Device target;
    private final org.apache.logging.log4j.Logger log = LogManager.getLogger(JnaLightDevice.class);
    private NativeLibrary library;
    private Pointer handle;
    private MvsSdkLease lease;
    private boolean opened;
    private String broadcastSelector;
    private String brightnessNode;
    private boolean floatBrightness;
    public JnaLightDevice(NativeLightConfig config, NativeLightConfig.Device target) { this.config = config; this.target = target; }
    @Override public void open() {
        if (Native.POINTER_SIZE != 8) throw new IllegalStateException("Native lights require a 64-bit JVM");
        try {
            library = MvsNativeLibrary.load(config.library());
            String key = MvsNativeLibrary.key(library);
            lease = MvsSdkLease.acquire(key, () -> call("MV_CC_Initialize"), () -> call("MV_CC_Finalize"));
            synchronized (MvsSdkLease.ENUMERATION_LOCK) {
                if (target.type().equals("com")) {
                    byte[] port = target.address().getBytes(StandardCharsets.UTF_8);
                    if (port.length >= 64) throw new IllegalArgumentException("SDK serial port name exceeds 63 bytes");
                    try (Memory ports = new Memory(5140)) {
                        ports.clear(); ports.setInt(0, 1); ports.write(4, port, 0, port.length);
                        call("MV_CAML_SetEnumSerialPorts", ports);
                    }
                }
                JnaMvsIoDevice.DeviceList list = new JnaMvsIoDevice.DeviceList();
                call("MV_CC_EnumDevices", target.type().equals("com") ? 8 : 1, list); list.read();
                if (list.count < 0 || list.count > 256) throw new IllegalStateException("Invalid MVS device count");
                List<Pointer> matches = new ArrayList<>();
                for (int i = 0; i < list.count; i++) {
                    Pointer info = list.devices[i]; if (info == null) continue;
                    int type = info.getInt(12);
                    String address, model, serial;
                    if (target.type().equals("com") && type == 8) {
                        address = cString(info, 32, 64); model = cString(info, 96, 64); serial = cString(info, 352, 64);
                    } else if (target.type().equals("ethernet") && type == 1) {
                        int ip = info.getInt(40);
                        address = ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255);
                        model = cString(info, 84, 32); serial = cString(info, 196, 16);
                    } else continue;
                    if (address.equals(target.address()) && target.modelPrefixes().stream().anyMatch(model::startsWith)
                            && (target.serial().isEmpty() || serial.equals(target.serial()))) matches.add(info);
                }
                if (matches.size() != 1) throw new IllegalStateException("Expected one configured light model at " + target.address() + ", found " + matches.size());
                PointerByReference ref = new PointerByReference();
                call("MV_CC_CreateHandle", ref, matches.get(0)); handle = ref.getValue();
                call("MV_CC_OpenDevice", handle, config.runtime().accessMode(), (short)config.runtime().switchoverKey()); opened = true;
            }
            for (int channel : target.channels()) {
                select(channel);
                List<String> sources = symbols(config.sourceNode());
                if (!sources.contains(target.sourceOn()) || !sources.contains(target.sourceOff())) throw new IllegalStateException("Light must expose configured source_on/off: " + sources);
            }
            var selectors = enumValue(config.selectorNode());
            Set<Integer> numericChannels = new HashSet<>();
            for (int i = 0; i < selectors.count; i++) if (selectors.supported[i] >= 1 && selectors.supported[i] <= 64) numericChannels.add(selectors.supported[i]);
            // Broadcast is allowed only if it cannot activate channels outside this configured device.
            if (config.runtime().useBroadcast() && numericChannels.equals(new HashSet<>(target.channels()))) {
                List<String> names = symbols(config.selectorNode());
                broadcastSelector = config.broadcastSelectors().stream().filter(names::contains).findFirst().orElse(null);
            }
            log.info("light event=connected device={} type={} address={} channels={}", target.id(), target.type(), target.address(), target.channels());
        } catch (RuntimeException | LinkageError e) { close(); throw e; }
    }
    private void select(int channel) {
        if (!opened || !target.channels().contains(channel)) throw new IllegalArgumentException("Disconnected or unconfigured light channel " + channel);
        JnaMvsIoDevice.EnumValue values = enumValue(config.selectorNode());
        boolean numeric = false;
        for (int i = 0; i < values.count; i++) if (values.supported[i] == channel) numeric = true;
        if (!numeric) throw new IllegalArgumentException("Unsupported light channel " + channel);
        call("MV_CC_SetEnumValue", handle, config.selectorNode(), channel);
    }
    @Override public void brightness(int channel, int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Brightness 0..255");
        value = target.sdkBrightness(value);
        select(channel);
        if (brightnessNode == null) {
            for (String node : config.brightnessNodes()) {
                IntValue integer = new IntValue();
                if (invoke("MV_CC_GetIntValueEx", handle, node, integer) == 0) {
                    integer.read(); brightnessNode = node; floatBrightness = false; break;
                }
                FloatValue floating = new FloatValue();
                if (invoke("MV_CC_GetFloatValue", handle, node, floating) == 0) {
                    floating.read(); brightnessNode = node; floatBrightness = true; break;
                }
            }
            if (brightnessNode == null) throw new IllegalStateException("No readable MV-LE brightness node");
        }
        if (floatBrightness) {
            FloatValue limit = new FloatValue(); call("MV_CC_GetFloatValue", handle, brightnessNode, limit); limit.read();
            if (value < limit.min || value > limit.max) throw new IllegalArgumentException("Brightness outside SDK range");
            call("MV_CC_SetFloatValue", handle, brightnessNode, (float)value);
        } else {
            IntValue limit = new IntValue(); call("MV_CC_GetIntValueEx", handle, brightnessNode, limit); limit.read();
            if (value < limit.min || value > limit.max || (limit.increment > 0 && (value - limit.min) % limit.increment != 0))
                throw new IllegalArgumentException("Brightness outside SDK range/increment");
            call("MV_CC_SetIntValueEx", handle, brightnessNode, (long)value);
        }
        log.debug("light event=brightness device={} channel={} value={} node={}", target.id(), channel, value, brightnessNode);
    }
    @Override public void source(int channel, boolean on) {
        select(channel);
        call("MV_CC_SetEnumValueByString", handle, config.sourceNode(), on ? target.sourceOn() : target.sourceOff());
        log.info("light event=source device={} channel={} state={}", target.id(), channel, on ? "on" : "off");
    }
    @Override public void sources(List<Integer> channels, boolean on) {
        if (broadcastSelector != null && channels.equals(target.channels())) {
            call("MV_CC_SetEnumValueByString", handle, config.selectorNode(), broadcastSelector);
            call("MV_CC_SetEnumValueByString", handle, config.sourceNode(), on ? target.sourceOn() : target.sourceOff());
            log.info("light event=broadcast_source device={} state={} selector={}", target.id(), on, broadcastSelector);
        } else LightDevice.super.sources(channels, on);
    }
    @Override public void checkConnection() { if (!opened) throw new IllegalStateException("Light disconnected"); enumValue(config.selectorNode()); }
    private JnaMvsIoDevice.EnumValue enumValue(String node) {
        JnaMvsIoDevice.EnumValue value = new JnaMvsIoDevice.EnumValue(); call("MV_CC_GetEnumValue", handle, node, value); value.read();
        if (value.count < 0 || value.count > 64) throw new IllegalStateException("Invalid enum " + node); return value;
    }
    private List<String> symbols(String node) {
        var values = enumValue(node); List<String> result = new ArrayList<>();
        for (int i = 0; i < values.count; i++) {
            var entry = new JnaMvsIoDevice.EnumEntry(); entry.value = values.supported[i]; entry.write();
            call("MV_CC_GetEnumEntrySymbolic", handle, node, entry); entry.read();
            int end = 0; while (end < entry.symbol.length && entry.symbol[end] != 0) end++;
            result.add(new String(entry.symbol, 0, end, StandardCharsets.UTF_8));
        }
        return result;
    }
    private int invoke(String name, Object... args) {
        long start = System.nanoTime();
        int code = library.getFunction(name, Platform.isWindows() ? Function.ALT_CONVENTION : Function.C_CONVENTION).invokeInt(args);
        log.debug("light event=sdk_call device={} operation={} result={} duration_ms={}", target.id(), name, String.format("0x%08x", code), (System.nanoTime()-start)/1_000_000.0);
        return code;
    }
    private void call(String name, Object... args) {
        int code = invoke(name, args); if (code != 0) throw new IllegalStateException(name + " MVS " + String.format("0x%08x", code));
    }
    private static String cString(Pointer info, int offset, int count) {
        byte[] bytes = info.getByteArray(offset, count); int end = 0; while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
    @Override public void close() {
        if (opened) { cleanup(() -> call("MV_CC_CloseDevice", handle)); opened = false; }
        if (handle != null) { cleanup(() -> call("MV_CC_DestroyHandle", handle)); handle = null; }
        if (lease != null) { cleanup(lease::close); lease = null; }
        brightnessNode = null; broadcastSelector = null;
        log.info("light event=closed device={}", target.id());
    }
    private void cleanup(Runnable action) { try { action.run(); } catch (RuntimeException | LinkageError e) { log.warn("light device={} cleanup failed", target.id(), e); } }
}
