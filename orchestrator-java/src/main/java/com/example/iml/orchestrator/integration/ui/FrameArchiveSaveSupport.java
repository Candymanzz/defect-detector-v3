package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.ui.archive.FrameArchivePaths;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveWriter;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveWriter.PreparedSave;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntSupplier;

final class FrameArchiveSaveSupport {
    private FrameArchiveSaveSupport() {
    }

    static void schedule(
            Logger log,
            FrameArchiveConfig config,
            ThreadPoolExecutor executor,
            FrameArchiveWriter writer,
            IntSupplier maxFrames,
            FrameArchiveService.SaveRequest request
    ) {
        PreparedSave prepared = snapshot(log, config, maxFrames, request, false);
        if (prepared == null) {
            return;
        }
        try {
            executor.execute(() -> writer.savePrepared(prepared));
        } catch (RejectedExecutionException e) {
            // Do not drop frames when the queue is full — write on the caller thread.
            writer.savePrepared(prepared);
        }
    }

    static boolean saveImmediately(
            Logger log,
            FrameArchiveConfig config,
            FrameArchiveWriter writer,
            IntSupplier maxFrames,
            FrameArchiveService.SaveRequest request
    ) {
        PreparedSave prepared = snapshot(log, config, maxFrames, request, true);
        if (prepared == null) {
            return false;
        }
        try {
            writer.savePrepared(prepared);
            return Files.isRegularFile(
                    FrameArchivePaths.frameDirectory(config.directory(), request.cameraId(), request.frameId())
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

    private static PreparedSave snapshot(
            Logger log,
            FrameArchiveConfig config,
            IntSupplier maxFrames,
            FrameArchiveService.SaveRequest request,
            boolean immediate
    ) {
        if (config == null || !config.enabled() || request == null
                || request.frameJpeg() == null || !Files.isRegularFile(request.frameJpeg())
                || maxFrames.getAsInt() <= 0) {
            return null;
        }
        try {
            byte[] frameBytes = Files.readAllBytes(request.frameJpeg());
            byte[] heatmapBytes = request.heatmapU8() != null && Files.isRegularFile(request.heatmapU8())
                    ? Files.readAllBytes(request.heatmapU8()) : null;
            return new PreparedSave(request, frameBytes, heatmapBytes);
        } catch (IOException e) {
            log.warn(
                    immediate ? "frame archive immediate save failed camera_id={} frame_id={}: {}"
                            : "frame archive snapshot failed camera_id={} frame_id={}: {}",
                    request.cameraId(), request.frameId(), e.getMessage()
            );
            return null;
        }
    }
}
