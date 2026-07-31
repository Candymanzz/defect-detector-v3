package com.example.iml.orchestrator.integration.ui.archive;

import com.example.iml.orchestrator.integration.ui.FrameArchiveService.ArchivedFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class FrameArchiveRetention {

    private FrameArchiveRetention() {
    }

    public static void trimAllCameras(Path archiveRoot, int maxFrames) throws IOException {
        if (!Files.isDirectory(archiveRoot)) {
            return;
        }
        try (Stream<Path> entries = Files.list(archiveRoot)) {
            for (Path cameraDir : entries.filter(Files::isDirectory).toList()) {
                String name = cameraDir.getFileName().toString();
                if (!name.startsWith("camera_")) {
                    continue;
                }
                try {
                    int cameraId = Integer.parseInt(name.substring("camera_".length()));
                    trimOldFrames(archiveRoot, cameraId, maxFrames);
                } catch (NumberFormatException ignored) {
                    // skip non-camera directories
                }
            }
        }
    }

    public static void trimOldFrames(Path archiveRoot, int cameraId, int maxFrames) throws IOException {
        if (maxFrames <= 0) {
            deleteCameraFrames(archiveRoot, cameraId);
            return;
        }
        Path cameraDir = FrameArchivePaths.cameraDirectory(archiveRoot, cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return;
        }
        // Newest by saved_at first; drop oldest when over the configured limit (ring buffer).
        List<ArchivedFrame> frames = new ArrayList<>();
        try (Stream<Path> entries = Files.list(cameraDir)) {
            for (Path frameDir : entries.filter(Files::isDirectory).toList()) {
                FrameArchiveIndex.parseFrameDir(frameDir).ifPresent(frames::add);
            }
        }
        if (frames.size() <= maxFrames) {
            return;
        }
        frames.sort(Comparator
                .comparingLong(ArchivedFrame::savedAtEpochMs)
                .reversed()
                .thenComparing(Comparator.comparingLong(ArchivedFrame::frameId).reversed()));
        for (int index = maxFrames; index < frames.size(); index++) {
            FrameArchivePaths.deleteFrameDirectory(
                    FrameArchivePaths.frameDirectory(archiveRoot, cameraId, frames.get(index).frameId())
            );
        }
    }

    public static void deleteCameraFrames(Path archiveRoot, int cameraId) throws IOException {
        Path cameraDir = FrameArchivePaths.cameraDirectory(archiveRoot, cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(cameraDir)) {
            entries.filter(Files::isDirectory).forEach(FrameArchivePaths::deleteFrameDirectory);
        }
    }

    public static int clearCamera(Path archiveRoot, int cameraId) throws IOException {
        Path cameraDir = FrameArchivePaths.cameraDirectory(archiveRoot, cameraId);
        if (!Files.isDirectory(cameraDir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> entries = Files.list(cameraDir)) {
            for (Path frameDir : entries.filter(Files::isDirectory).toList()) {
                if (FrameArchivePaths.parseFrameId(frameDir) >= 0) {
                    FrameArchivePaths.deleteFrameDirectory(frameDir);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    public static int clearAll(Path archiveRoot) throws IOException {
        if (!Files.isDirectory(archiveRoot)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> entries = Files.list(archiveRoot)) {
            for (Path cameraDir : entries.filter(Files::isDirectory).toList()) {
                String name = cameraDir.getFileName().toString();
                if (!name.startsWith("camera_")) {
                    continue;
                }
                try {
                    deleted += clearCamera(archiveRoot, Integer.parseInt(name.substring("camera_".length())));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return deleted;
    }
}
