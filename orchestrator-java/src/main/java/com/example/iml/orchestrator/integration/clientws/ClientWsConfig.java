package com.example.iml.orchestrator.integration.clientws;

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
        /**
         * Если {@code true}, после WS-событий (пакет эталонов, FP hot-update, смена ракурса) оркестратор
         * рассылает команды в пул HTTP-клиентов analisSurface. По умолчанию {@code false}.
         */
        boolean analisSurfaceBundleSyncEnabled,
        /**
         * Ракурс пакета (0…4), чей {@code ShmFrameRef} подставляется в {@code reference_shm_*} для java-geometry (Фаза 6).
         * {@code -1} — использовать {@code joint_view_index} из принятого пакета.
         */
        int geometryReferenceViewIndex
) {

    private static final int PROTOCOL_VERSION = 1;

    public static ClientWsConfig disabled() {
        return new ClientWsConfig(false, "127.0.0.1", 8765, "/", true, 20_000, 90_000, false, -1);
    }

    @SuppressWarnings("unchecked")
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
        boolean analisSurfaceSync = YamlScalars.toBool(m.get("analis_surface_bundle_sync_enabled"), false);
        int geomRef = YamlScalars.toInt(m.get("geometry_reference_view_index"), -1);
        if (geomRef < -1 || geomRef > 4) {
            geomRef = -1;
        }
        return new ClientWsConfig(true, host, port, path, replace, ping, idle, analisSurfaceSync, geomRef);
    }

    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }
}
