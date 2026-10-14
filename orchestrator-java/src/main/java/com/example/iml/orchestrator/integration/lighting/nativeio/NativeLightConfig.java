package com.example.iml.orchestrator.integration.lighting.nativeio;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import java.util.*;

public record NativeLightConfig(String library, int commandTimeoutMs, int reconnectMs, int queueSize,
                                int shutdownTimeoutMs, String selectorNode, String sourceNode,
                                List<String> brightnessNodes, List<String> broadcastSelectors, List<Device> devices, Map<Integer, Route> routes, RuntimeSettings runtime) {
    public record Device(String id, String type, String address, String serial, List<Integer> channels,
                         List<String> modelPrefixes, String sourceOn, String sourceOff, int brightnessMin, int brightnessMax) {
        public int sdkBrightness(int normalized) {
            return brightnessMin + Math.round(normalized * (brightnessMax - brightnessMin) / 255f);
        }
    }
    public record RuntimeSettings(int healthCheckMs, int workerPollMs, int initialBrightness,
                                  boolean useBroadcast, boolean refreshBeforeOn, int accessMode, int switchoverKey) { }
    public record Route(int cameraNumber, String deviceId, List<Integer> channels) { }
    public static boolean nativeBackend(Map<String, Object> root) {
        Map<?, ?> light = root != null && root.get("light_servers") instanceof Map<?, ?> m ? m : Map.of();
        String backend = Objects.toString(light.get("backend"), "native");
        if (!Set.of("native", "http").contains(backend)) throw new IllegalArgumentException("light_servers.backend: native or http");
        return backend.equals("native");
    }
    public static NativeLightConfig parse(Map<String, Object> root) {
        Map<?, ?> light = root.get("light_servers") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> cfg = light.get("sdk") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> hardware = root.get("light_hardware") instanceof Map<?, ?> m ? m : Map.of();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String library = text(cfg, windows ? "library_windows" : "library_linux",
                text(cfg, "library", windows ? "MvCameraControl" : System.getenv().getOrDefault("MVS_ROOT", "/opt/MVS") + "/lib/64/libMvCameraControl.so"));
        List<Device> devices = new ArrayList<>(); Set<String> ids = new HashSet<>(), addresses = new HashSet<>();
        if (hardware.get("devices") instanceof List<?> list) for (Object item : list) {
            if (!(item instanceof Map<?, ?> device)) throw new IllegalArgumentException("light_hardware.devices: expected mappings");
            if (!YamlScalars.toBool(device.get("enabled"), true)) continue;
            String id = text(device, "id", ""), type = text(device, "type", "");
            if (!Set.of("ethernet", "com").contains(type)) throw new IllegalArgumentException("Light device type: ethernet or com");
            String address = type.equals("ethernet") ? text(device, "ip", "")
                    : text(device, windows ? "com_port_windows" : "com_port_linux", text(device, "com_port", ""));
            if (type.equals("com")) address = sdkPort(address);
            if (id.isEmpty() || address.isEmpty() || !ids.add(id) || !addresses.add(type + ":" + address))
                throw new IllegalArgumentException("Light devices need unique id/address and an explicit IP/serial port");
            List<String> models = strings(device.get("model_prefixes"), List.of("MV-LE"));
            int min = YamlScalars.toInt(device.get("brightness_min"), 0), max = YamlScalars.toInt(device.get("brightness_max"), 255);
            if (models.isEmpty() || models.stream().anyMatch(String::isBlank) || min < 0 || max <= min)
                throw new IllegalArgumentException("Light model_prefixes / brightness_min,max are invalid: " + id);
            for (Map<?, ?> section : List.of(cfg, device)) for (String key : List.of("source_on", "source_off"))
                if (section.containsKey(key) && !(section.get(key) instanceof String))
                    throw new IllegalArgumentException("Light " + key + " must be a quoted YAML string");
            String sourceOn = text(device, "source_on", text(cfg, "source_on", "On"));
            String sourceOff = text(device, "source_off", text(cfg, "source_off", "Off"));
            if (sourceOn.isBlank() || sourceOff.isBlank() || sourceOn.equals(sourceOff)) throw new IllegalArgumentException("Light source_on/off must differ");
            devices.add(new Device(id, type, address, text(device, "serial", ""), channels(device.get("channels"), true), models, sourceOn, sourceOff, min, max));
        }
        if (devices.isEmpty()) throw new IllegalArgumentException("Enabled native lights require light_hardware.devices");
        Map<Integer, Route> routes = new TreeMap<>(); Set<String> assigned = new HashSet<>();
        if (hardware.get("camera_routes") instanceof List<?> list) for (Object item : list) {
            if (!(item instanceof Map<?, ?> route)) throw new IllegalArgumentException("light_hardware.camera_routes: expected mappings");
            int number = YamlScalars.toInt(route.get("camera_number"), 0);
            String id = text(route, "device_id", "");
            Device device = devices.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown light device " + id));
            List<Integer> channels = channels(route.get("channels"), false);
            if (number < 1 || routes.putIfAbsent(number, new Route(number, id, channels)) != null || !device.channels().containsAll(channels))
                throw new IllegalArgumentException("Invalid light camera route " + number);
            for (int channel : channels) if (!assigned.add(id + ":" + channel))
                throw new IllegalArgumentException("Light channel assigned to multiple cameras: " + id + ":" + channel);
        }
        List<String> brightness = cfg.get("brightness_nodes") instanceof List<?> list ? list.stream().map(Object::toString).toList()
                : List.of("LightBrightness", "LightControllerBrightness", "LightIntensity", "LightControllerIntensity", "LightChannelBrightness", "LightControlBrightness");
        if (library.isBlank() || brightness.isEmpty()) throw new IllegalArgumentException("Light SDK library/brightness nodes must not be empty");
        int accessMode = positive(cfg, "open_access_mode", 1);
        if (!Set.of(1, 2, 3, 4, 5, 6, 7).contains(accessMode)) throw new IllegalArgumentException("open_access_mode: SDK value 1..7");
        int switchoverKey = YamlScalars.toInt(cfg.get("switchover_key"), 0);
        if (switchoverKey < 0 || switchoverKey > 65535) throw new IllegalArgumentException("switchover_key: 0..65535");
        int initialBrightness = YamlScalars.toInt(cfg.get("initial_brightness"), 255);
        if (initialBrightness < 0 || initialBrightness > 255) throw new IllegalArgumentException("initial_brightness: 0..255");
        return new NativeLightConfig(library, positive(cfg, "command_timeout_ms", YamlScalars.toInt(light.get("timeout_ms"), 20000)),
                positive(cfg, "reconnect_ms", 1000), positive(cfg, "queue_size", 64), positive(cfg, "shutdown_timeout_ms", 5000),
                text(cfg, "selector_node", "LightControllerSelector"), text(cfg, "source_node", "LightControllerSource"),
                brightness, cfg.get("broadcast_selectors") instanceof List<?> list ? list.stream().map(Object::toString).toList()
                        : List.of("All", "ChannelAll", "LightControllerAll", "SelectorAll", "LightAll"),
                List.copyOf(devices), Map.copyOf(routes), new RuntimeSettings(
                    positive(cfg, "health_check_ms", positive(cfg, "reconnect_ms", 1000)), positive(cfg, "worker_poll_ms", 200),
                    initialBrightness, YamlScalars.toBool(cfg.get("use_broadcast"), true), YamlScalars.toBool(cfg.get("refresh_before_on"), true), accessMode, switchoverKey));
    }
    public static String sdkPort(String value) {
        String port = value.trim();
        if (port.isEmpty()) return "";
        if (port.regionMatches(true, 0, "COM_Port#", 0, 9)) port = port.substring(9);
        if (port.startsWith("/dev/")) port = port.substring(5);
        if (port.matches("(?i)COM[0-9]+")) port = port.toUpperCase(Locale.ROOT);
        return "COM_Port#" + port;
    }
    private static List<Integer> channels(Object raw, boolean count) {
        List<Integer> result = new ArrayList<>();
        if (raw instanceof List<?> list) list.forEach(v -> result.add(Integer.parseInt(v.toString())));
        else if (raw != null) {
            int n = Integer.parseInt(raw.toString());
            if (count) { if (n < 1 || n > 64) throw new IllegalArgumentException("Light channel count 1..64"); for (int i = 1; i <= n; i++) result.add(i); }
            else result.add(n);
        }
        if (result.isEmpty() || result.stream().anyMatch(n -> n < 1 || n > 64) || new HashSet<>(result).size() != result.size())
            throw new IllegalArgumentException("Specify unique light channels 1..64");
        return List.copyOf(result);
    }
    private static List<String> strings(Object raw, List<String> fallback) {
        return raw instanceof List<?> list ? list.stream().map(v -> v.toString().trim()).toList() : fallback;
    }
    private static String text(Map<?, ?> map, String key, String fallback) { return Objects.toString(map.get(key), fallback).trim(); }
    private static int positive(Map<?, ?> map, String key, int fallback) {
        int value = YamlScalars.toInt(map.get(key), fallback);
        if (value < 1) throw new IllegalArgumentException("Light " + key + " must be positive"); return value;
    }
}
