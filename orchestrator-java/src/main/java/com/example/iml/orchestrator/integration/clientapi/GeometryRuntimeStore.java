package com.example.iml.orchestrator.integration.clientapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted runtime overrides for geometry / inspection thresholds (survives orchestrator restarts).
 */
public final class GeometryRuntimeStore {

    private static final Logger LOG = LogManager.getLogger(GeometryRuntimeStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    private final Path storagePath;
    private final Object lock = new Object();
    private final Map<String, Map<String, Object>> byProfile = new LinkedHashMap<>();

    private GeometryRuntimeStore(Path storagePath) {
        this.storagePath = storagePath;
    }

    public static GeometryRuntimeStore open(Path storagePath) throws IOException {
        GeometryRuntimeStore store = new GeometryRuntimeStore(storagePath);
        store.load();
        return store;
    }

    public Path storagePath() {
        return storagePath;
    }

    public Map<String, Map<String, Object>> allProfiles() {
        synchronized (lock) {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : byProfile.entrySet()) {
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
    }

    public void replaceProfileAndSave(String profile, Map<String, Object> overrides) throws IOException {
        String profileKey = normalizeProfile(profile);
        synchronized (lock) {
            if (overrides == null || overrides.isEmpty()) {
                byProfile.remove(profileKey);
            } else {
                byProfile.put(profileKey, new LinkedHashMap<>(overrides));
            }
            persistLocked();
        }
    }

    public void removeProfileAndSave(String profile) throws IOException {
        String profileKey = normalizeProfile(profile);
        synchronized (lock) {
            byProfile.remove(profileKey);
            persistLocked();
        }
    }

    public void clearAllAndSave() throws IOException {
        synchronized (lock) {
            byProfile.clear();
            persistLocked();
        }
    }

    private void load() throws IOException {
        synchronized (lock) {
            byProfile.clear();
            if (storagePath == null || !Files.isRegularFile(storagePath)) {
                return;
            }
            String raw = Files.readString(storagePath);
            if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1);
            }
            Map<String, Object> root = JSON.readValue(raw, new TypeReference<>() {});
            Object profilesRaw = root == null ? null : root.get("profiles");
            if (!(profilesRaw instanceof Map<?, ?> profilesMap)) {
                return;
            }
            for (Map.Entry<?, ?> entry : profilesMap.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> overridesRaw)) {
                    continue;
                }
                String profile = normalizeProfile(String.valueOf(entry.getKey()));
                Map<String, Object> overrides = new LinkedHashMap<>();
                for (Map.Entry<?, ?> overrideEntry : overridesRaw.entrySet()) {
                    if (overrideEntry.getKey() == null || overrideEntry.getValue() == null) {
                        continue;
                    }
                    overrides.put(String.valueOf(overrideEntry.getKey()), overrideEntry.getValue());
                }
                if (!overrides.isEmpty()) {
                    byProfile.put(profile, overrides);
                }
            }
            LOG.info(
                    "geometry runtime store loaded path={} profiles={}",
                    storagePath.toAbsolutePath(),
                    byProfile.size()
            );
        }
    }

    private void persistLocked() throws IOException {
        if (storagePath == null) {
            return;
        }
        Files.createDirectories(storagePath.getParent());
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : byProfile.entrySet()) {
            profiles.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("profiles", profiles);
        Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), root);
        try {
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.debug(
                "geometry runtime store saved path={} profiles={}",
                storagePath.toAbsolutePath(),
                byProfile.size()
        );
    }

    private static String normalizeProfile(String analysisProfile) {
        if (analysisProfile == null) {
            return "__default__";
        }
        String trimmed = analysisProfile.trim();
        return trimmed.isEmpty() ? "__default__" : trimmed;
    }
}
