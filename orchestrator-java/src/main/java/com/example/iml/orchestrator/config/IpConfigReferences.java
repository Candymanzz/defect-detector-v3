package com.example.iml.orchestrator.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves machine-local IPv4 references after all imports have been merged. */
final class IpConfigReferences {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{ip:([^}]+)}");

    private IpConfigReferences() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolve(Map<String, Object> root, Path directory) throws IOException {
        if (root == null) return null;
        Map<?, ?> addresses = Map.of();
        if (root.containsKey("ip_config")) {
            Object filename = root.get("ip_config");
            if (!(filename instanceof String name) || name.isBlank())
                throw new IOException("ip_config must name a local YAML file");
            Path file = directory.resolve(name).normalize();
            try (InputStream input = Files.newInputStream(file)) {
                Object loaded = new Yaml().load(input);
                if (!(loaded instanceof Map<?, ?> map))
                    throw new IOException("IP config must contain a mapping: " + file);
                addresses = map;
            } catch (IOException ex) {
                throw new IOException("Cannot load local IP config " + file
                        + "; create it with python3 deploy/init-ips.py", ex);
            }
            for (var entry : addresses.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String value)
                        || !isIpv4(value))
                    throw new IOException("Invalid IPv4 address in IP config, key: " + entry.getKey());
            }
        }
        return (Map<String, Object>) replace(root, addresses);
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static Object replace(Object value, Map<?, ?> addresses) throws IOException {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet())
                result.put(String.valueOf(entry.getKey()), replace(entry.getValue(), addresses));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(replace(item, addresses));
            return result;
        }
        if (!(value instanceof String text)) return value;
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object address = addresses.get(matcher.group(1));
            if (!(address instanceof String))
                throw new IOException("Missing IP config key: " + matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement((String) address));
        }
        matcher.appendTail(result);
        if (result.indexOf("${ip:") >= 0) throw new IOException("Malformed IP reference: " + text);
        return result.toString();
    }
}
