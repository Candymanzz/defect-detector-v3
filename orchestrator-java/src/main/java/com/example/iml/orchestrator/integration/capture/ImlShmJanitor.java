package com.example.iml.orchestrator.integration.capture;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Удаляет устаревшие и осиротевшие файлы в {@code iml_shm}.
 * <p>
 * До фикса перезаписи оркестратор создавал уникальное имя на каждый кадр
 * ({@code _f{frameId}_}, UUID, {@code .bgr}) — эти файлы здесь не растут бесконечно.
 */
public final class ImlShmJanitor {

    private static final Pattern STABLE_FILE = Pattern.compile(
            "^iml_cam_\\d+_frame$"
                    + "|^iml_pos_cam_\\d+$"
                    + "|^iml_ds_[a-z_]+_cam\\d+$"
                    + "|^iml_py_ds_(cur|ref)_cam\\d+$"
                    + "|^iml_ui_(inspect|heatmap)_cam_\\d+$"
                    + "|^iml_ui_inspect_cam_\\d+_f\\d+$"
    );

    private ImlShmJanitor() {
    }

    /** При старте: удалить всё, что не соответствует стабильным шаблонам имён. */
    public static void purgeStaleFiles(Logger log) {
        purge(log, false);
    }

    /** При остановке: удалить буферы оркестратора, оставить {@code iml_cam_*}. */
    public static void purgeOrchestratorBuffers(Logger log) {
        purge(log, true);
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
                    if (!isOrchestratorOwnedBuffer(name)) {
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
