package com.example.iml.orchestrator.integration.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Безопасное чтение Map/List из YAML без unchecked-cast.
 * Generics стираются в runtime — копируем в типизированные коллекции через цикл.
 */
public final class YamlMaps {

    private YamlMaps() {
    }

    public static Map<?, ?> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? map : Map.of();
    }

    public static Optional<Map<?, ?>> optionalMap(Object raw) {
        return raw instanceof Map<?, ?> map ? Optional.of(map) : Optional.empty();
    }

    /**
     * Копия map с ключами {@link String}. Пустой / не-map → {@link Map#of()}.
     */
    public static Map<String, Object> stringObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(source.size());
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** Как {@link #stringObjectMap}, но не-map → {@code null} (когда важен «секции нет»). */
    public static Map<String, Object> stringObjectMapOrNull(Object raw) {
        return raw instanceof Map<?, ?> ? stringObjectMap(raw) : null;
    }

    public static List<?> asList(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    public static List<Map<String, Object>> listOfStringObjectMaps(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                out.add(stringObjectMap(item));
            }
        }
        return out;
    }

    public static void putAllStringKeys(Map<String, Object> target, Object raw) {
        if (!(raw instanceof Map<?, ?> source) || target == null) {
            return;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            target.put(String.valueOf(entry.getKey()), entry.getValue());
        }
    }
}
