package com.example.iml.orchestrator.integration.ui.archive;

import com.example.iml.orchestrator.integration.ui.FrameArchiveService.SaveRequest;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveWriter.PreparedSave;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/** Snapshot + async/sync save helpers for {@link com.example.iml.orchestrator.integration.ui.FrameArchiveService}. */
public final class FrameArchiveSaveSupport {

    private FrameArchiveSaveSupport() {
    }

    public static void scheduleSave(
            SaveRequest request,
            boolean enabled,
            int maxFramesPerCamera,
            FrameArchiveWriter writer,
            ThreadPoolExecutor executor,
            Logger log
    ) {
        if (!enabled || request == null || request.frameJpeg() == null || !Files.isRegularFile(request.frameJpeg())) {
            return;
        }
        if (maxFramesPerCamera <= 0) {
            return;
        }
        // Snapshot bytes while source paths are still valid (UI finally / next inspection may delete them).
        final byte[] frameBytes;
        final byte[] heatmapBytes;
        try {
            frameBytes = Files.readAllBytes(request.frameJpeg());
            heatmapBytes = request.heatmapU8() != null && Files.isRegularFile(request.heatmapU8())
                    ? Files.readAllBytes(request.heatmapU8())
                    : null;
        } catch (IOException e) {
            log.warn(
                    "frame archive snapshot failed camera_id={} frame_id={}: {}",
                    request.cameraId(),
                    request.frameId(),
                    e.getMessage()
            );
            return;
        }
        PreparedSave prepared = new PreparedSave(request, frameBytes, heatmapBytes);
        try {
            executor.execute(() -> writer.savePrepared(prepared));
        } catch (RejectedExecutionException e) {
            // Do not drop frames when the queue is full — write on the caller thread.
            writer.savePrepared(prepared);
        }
    }

    /**
     * Snapshot + write immediately on the caller thread. Safe to call before ephemeral UI files are deleted.
     */
    public static boolean saveImmediately(
            SaveRequest request,
            boolean enabled,
            int maxFramesPerCamera,
            java.nio.file.Path archiveDirectory,
            FrameArchiveWriter writer,
            Logger log
    ) {
        if (!enabled || request == null || request.frameJpeg() == null || !Files.isRegularFile(request.frameJpeg())) {
            return false;
        }
        if (maxFramesPerCamera <= 0) {
            return false;
        }
        try {
            byte[] frameBytes = Files.readAllBytes(request.frameJpeg());
            byte[] heatmapBytes = request.heatmapU8() != null && Files.isRegularFile(request.heatmapU8())
                    ? Files.readAllBytes(request.heatmapU8())
                    : null;
            writer.savePrepared(new PreparedSave(request, frameBytes, heatmapBytes));
            return Files.isRegularFile(
                    FrameArchivePaths.frameDirectory(archiveDirectory, request.cameraId(), request.frameId())
                            .resolve("frame.jpg")
            );
        } catch (Exception e) {
            log.warn(
                    "frame archive immediate save failed camera_id={} frame_id={}: {}",
                    request.cameraId(),
                    request.frameId(),
                    e.getMessage()
            );
            return false;
        }
    }
}
