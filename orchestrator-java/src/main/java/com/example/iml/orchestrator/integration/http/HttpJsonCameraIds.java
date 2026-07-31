package com.example.iml.orchestrator.integration.http;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parse {@code cameraId} / {@code cameraIds} from JSON request bodies.
 */
public final class HttpJsonCameraIds {

    private HttpJsonCameraIds() {
    }

    public static List<Integer> parse(Map<String, Object> body) {
        Set<Integer> out = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Integer singleId = parseOne(body.get("cameraId"));
        if (singleId != null) {
            out.add(singleId);
        }
        Object many = body.get("cameraIds");
        if (many instanceof Iterable<?> iterable) {
            for (Object rawId : iterable) {
                Integer cameraId = parseOne(rawId);
                if (cameraId != null) {
                    out.add(cameraId);
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static Integer parseOne(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
