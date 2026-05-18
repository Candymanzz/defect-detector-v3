package com.example.iml.orchestrator.integration.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Заголовки кадра в SHM для логов: без секретов и без больших вложенных структур (Фаза 8 — наблюдаемость).
 */
public final class ShmRefLogSanitizer {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "camera_id",
            "frame_id",
            "shm_name",
            "shm_offset",
            "width",
            "height",
            "stride",
            "pixel_format",
            "channels",
            "expires_at_ms",
            "ttl_ms",
            "op"
    );

    private ShmRefLogSanitizer() {
    }

    /**
     * Копия только безопасных скалярных полей; {@code read_token} не копируется (в лог пишется флаг наличия).
     */
    public static Map<String, Object> redactFrameHeader(Map<String, Object> header) {
        if (header == null || header.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : ALLOWED_KEYS) {
            if (!header.containsKey(k)) {
                continue;
            }
            Object v = header.get(k);
            if (v == null) {
                continue;
            }
            if (v instanceof String s) {
                if (!s.isEmpty()) {
                    out.put(k, s);
                }
            } else if (v instanceof Number || v instanceof Boolean) {
                out.put(k, v);
            } else {
                out.put(k, String.valueOf(v));
            }
        }
        Object tok = header.get("read_token");
        if (tok != null && !String.valueOf(tok).trim().isEmpty()) {
            out.put("read_token", "<redacted>");
        }
        return out;
    }
}
