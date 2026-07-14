package com.example.iml.orchestrator.integration.capture;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Удаляет устаревшие и осиротевшие файлы в {@code iml_shm}.
 * <p>
 * До фикса перезаписи оркестратор создавал уникальное имя на каждый кадр
 * ({@code _f{frameId}_}, UUID, {@code .bgr}) — эти файлы здесь не растут бесконечно.
 * Per-cycle pins ({@code iml_line_pin_*}) освобождаются через
 * {@link #releaseEphemeralCaptureBuffers(Map, Logger)} после инспекции.
 */
public final class ImlShmJanitor {

    private static final Pattern STABLE_FILE = Pattern.compile(
            "^iml_cam_\\d+_frame$"
                    + "|^iml_ref_cam\\d+$"
                    + "|^iml_pos_cam_\\d+$"
                    + "|^iml_ds_[a-z_]+_cam\\d+$"
                    + "|^iml_py_ds_(cur|ref)_cam\\d+$"
                    + "|^iml_ui_(inspect|heatmap)_cam_\\d+$"
    );

    private static final Pattern LINE_PIN_FILE = Pattern.compile("^iml_line_pin_cam\\d+_f\\d+$");

    private ImlShmJanitor() {
    }

    /** При старте: удалить всё, что не соответствует стабильным шаблонам имён. */
    public static void purgeStaleFiles(Logger log) {
        purge(log, false);
    }

    /** При остановке: удалить буферы оркестратора и line-pin’ы, оставить {@code iml_cam_*} / {@code iml_ref_*}. */
    public static void purgeOrchestratorBuffers(Logger log) {
        purge(log, true);
    }

    /**
     * Удаляет ephemeral line-pin файлы, на которые ссылается заголовок кадра.
     * Не трогает эталоны ({@code iml_ref_*}), ring ({@code iml_cam_*}) и стабильные буферы.
     */
    public static void releaseEphemeralCaptureBuffers(Map<String, Object> header, Logger log) {
        if (header == null || header.isEmpty()) {
            return;
        }
        Set<String> candidates = new LinkedHashSet<>(4);
        addShmCandidate(candidates, header.get("shm_name"));
        addShmCandidate(candidates, header.get("original_shm_name"));
        // line_pin_source_shm is the worker ring (iml_cam_*) — never delete it.
        int deleted = 0;
        long freed = 0L;
        for (String base : candidates) {
            if (!isEphemeralLinePin(base)) {
                continue;
            }
            Path path = FrameJpegWriter.imlShmFilePath(base);
            long size = safeSize(path);
            if (deleteQuietly(path)) {
                deleted++;
                freed += size;
            }
        }
        if (deleted > 0 && log != null) {
            log.debug(
                    "iml_shm cycle cleanup deleted={} freed_mb={} cam={}",
                    deleted,
                    freed / (1024L * 1024L),
                    header.get("camera_id")
            );
        }
    }

    public static boolean isEphemeralLinePin(String shmNameOrBase) {
        String base = shmBaseName(shmNameOrBase);
        return base != null && LINE_PIN_FILE.matcher(base).matches();
    }

    private static void purge(Logger log, boolean orchestratorBuffersOnly) {
        Path dir = imlShmDirectory();
        if (!Files.isDirectory(dir)) {
            return;
        }
        long deleted = 0L;
        long freedBytes = 0L;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (orchestratorBuffersOnly) {
                    if (!isOrchestratorOwnedBuffer(name) && !isEphemeralLinePin(name)) {
                        continue;
                    }
                } else if (STABLE_FILE.matcher(name).matches()) {
                    continue;
                }
                long size = safeSize(entry);
                if (deleteQuietly(entry)) {
                    deleted++;
                    freedBytes += size;
                }
            }
        } catch (IOException e) {
            if (log != null) {
                log.warn("iml_shm cleanup failed dir={}: {}", dir, e.getMessage());
            }
            return;
        }
        if (deleted > 0 && log != null) {
            log.info(
                    "iml_shm cleanup dir={} deleted={} freed_mb={} orchestrator_only={}",
                    dir,
                    deleted,
                    freedBytes / (1024L * 1024L),
                    orchestratorBuffersOnly
            );
        }
    }

    public static Path imlShmDirectory() {
        return FrameJpegWriter.imlShmFilePath("_probe").getParent();
    }

    public static boolean isDedicatedOrchestratorBuffer(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return false;
        }
        return baseName.startsWith("iml_ds_")
                || baseName.startsWith("iml_py_ds_")
                || baseName.startsWith("iml_pos_")
                || baseName.startsWith("iml_ui_");
    }

    private static boolean isOrchestratorOwnedBuffer(String name) {
        return name.startsWith("iml_ds_")
                || name.startsWith("iml_py_ds_")
                || name.startsWith("iml_pos_")
                || name.startsWith("iml_ui_");
    }

    private static void addShmCandidate(Set<String> out, Object raw) {
        String base = shmBaseName(raw == null ? null : String.valueOf(raw));
        if (base != null) {
            out.add(base);
        }
    }

    private static String shmBaseName(String shmName) {
        if (shmName == null || shmName.isBlank() || "null".equals(shmName)) {
            return null;
        }
        String name = shmName.trim();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        name = name.replace('/', '_');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".bin")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? null : name;
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ignored) {
            return false;
        }
    }
}
