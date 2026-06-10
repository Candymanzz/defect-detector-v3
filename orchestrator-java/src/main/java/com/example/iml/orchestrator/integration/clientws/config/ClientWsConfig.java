package com.example.iml.orchestrator.integration.clientws.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/**
 * Секция {@code client_ws} в корневом YAML.
 */
public record ClientWsConfig(
        boolean enabled,
        String host,
        int port,
        String path,
        boolean replaceExistingSession,
        int pingIntervalMs,
        int readIdleTimeoutMs,
        boolean kopcheniBundleSyncEnabled,
        double inspectScale
) {

    private static final int PROTOCOL_VERSION = 1;

    public static ClientWsConfig disabled() {
        return new ClientWsConfig(false, "127.0.0.1", 8765, "/", true, 20_000, 90_000, false, 1.0d);
    }

    public static ClientWsConfig fromRootYaml(Map<String, Object> root) {
        if (root == null) {
            return disabled();
        }
        Object raw = root.get("client_ws");
        if (!(raw instanceof Map<?, ?> m)) {
            return disabled();
        }
        boolean en = YamlScalars.toBool(m.get("enabled"), false);
        if (!en) {
            return disabled();
        }
        String host = "127.0.0.1";
        Object hostObj = m.get("host");
        if (hostObj != null) {
            host = String.valueOf(hostObj).trim();
        }
        if (host.isEmpty()) {
            host = "127.0.0.1";
        }
        int port = YamlScalars.toInt(m.get("port"), 8765);
        String path = "/";
        Object pathObj = m.get("path");
        if (pathObj != null) {
            path = String.valueOf(pathObj).trim();
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        boolean replace = YamlScalars.toBool(m.get("replace_existing_session"), true);
        int ping = Math.max(5_000, YamlScalars.toInt(m.get("ping_interval_ms"), 20_000));
        int idle = Math.max(10_000, YamlScalars.toInt(m.get("read_idle_timeout_ms"), 90_000));
        boolean kopSync = YamlScalars.toBool(m.get("kopcheni_bundle_sync_enabled"), false);
        double inspectScale = 1.0d;
        Object pyRaw = root.get("python_detector");
        if (pyRaw instanceof Map<?, ?> pyMap) {
            inspectScale = YamlScalars.toDouble(pyMap.get("inspect_scale"), 1.0d);
        }
        if (!Double.isFinite(inspectScale) || inspectScale <= 0.0d) {
            inspectScale = 1.0d;
        }
        return new ClientWsConfig(true, host, port, path, replace, ping, idle, kopSync, inspectScale);
    }

    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }
}
