package com.example.iml.orchestrator.integration.capture;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Удаляет устаревшие и осиротевшие файлы в {@code iml_shm}.
 * <p>
 * До фикса перезаписи оркестратор создавал уникальное имя на каждый кадр
 * ({@code _f{frameId}_}, UUID, {@code .bgr}) — эти файлы здесь не растут бесконечно.
 * Per-cycle pins ({@code iml_line_pin_*}) освобождаются через
 * {@link #releaseEphemeralCaptureBuffers(Map, Logger)} после инспекции.
 * Периодический {@link #purgeEphemeralOlderThan(Duration, Logger)} подбирает
 * застрявшие pin’ы при timeout / пропущенном release.
 */
public final class ImlShmJanitor {

    /** TTL для осиротевших line-pin: цикл ~4 с, запас на backlog и UI sidecar. */
    public static final Duration DEFAULT_EPHEMERAL_TTL = Duration.ofSeconds(45);

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
     * Runtime sweep: удаляет только ephemeral line-pin’ы старше {@code maxAge}.
     * Не трогает ring/ref/стабильные буферы оркестратора.
     */
    public static void purgeEphemeralOlderThan(Duration maxAge, Logger log) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return;
        }
        Path dir = imlShmDirectory();
        if (!Files.isDirectory(dir)) {
            return;
        }
        long cutoffMs = System.currentTimeMillis() - maxAge.toMillis();
        long deleted = 0L;
        long freedBytes = 0L;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (!ImlShmNames.isEphemeralLinePin(name)) {
                    continue;
                }
                if (ImlShmFileOps.fileTimeMs(entry) >= cutoffMs) {
                    continue;
                }
                long size = ImlShmFileOps.safeSize(entry);
                if (ImlShmFileOps.deleteQuietly(entry)) {
                    deleted++;
                    freedBytes += size;
                }
            }
        } catch (IOException e) {
            if (log != null) {
                log.warn("iml_shm ttl cleanup failed dir={}: {}", dir, e.getMessage());
            }
            return;
        }
        if (deleted > 0 && log != null) {
            log.info(
                    "iml_shm ttl cleanup dir={} deleted={} freed_mb={} max_age_s={}",
                    dir,
                    deleted,
                    freedBytes / (1024L * 1024L),
                    maxAge.toSeconds()
            );
        }
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
        ImlShmNames.addShmCandidate(candidates, header.get("shm_name"));
        ImlShmNames.addShmCandidate(candidates, header.get("original_shm_name"));
        // line_pin_source_shm is the worker ring (iml_cam_*) — never delete it.
        int deleted = 0;
        long freed = 0L;
        for (String base : candidates) {
            if (!ImlShmNames.isEphemeralLinePin(base)) {
                continue;
            }
            Path path = FrameJpegWriter.imlShmFilePath(base);
            long size = ImlShmFileOps.safeSize(path);
            if (ImlShmFileOps.deleteQuietly(path)) {
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
        return ImlShmNames.isEphemeralLinePin(shmNameOrBase);
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
                    if (!ImlShmNames.isOrchestratorOwnedBuffer(name) && !ImlShmNames.isEphemeralLinePin(name)) {
                        continue;
                    }
                } else if (ImlShmNames.STABLE_FILE.matcher(name).matches()) {
                    continue;
                }
                long size = ImlShmFileOps.safeSize(entry);
                if (ImlShmFileOps.deleteQuietly(entry)) {
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
        return ImlShmNames.isDedicatedOrchestratorBuffer(baseName);
    }
}
