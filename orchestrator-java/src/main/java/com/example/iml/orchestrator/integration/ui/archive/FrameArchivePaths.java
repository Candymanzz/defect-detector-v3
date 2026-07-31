package com.example.iml.orchestrator.integration.ui.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrameArchivePaths {

    private static final Pattern FRAME_DIR = Pattern.compile("^f_(\\d+)$");

    private FrameArchivePaths() {
    }

    public static Path cameraDirectory(Path archiveRoot, int cameraId) {
        return archiveRoot.resolve("camera_" + cameraId);
    }

    public static Path frameDirectory(Path archiveRoot, int cameraId, long frameId) {
        return cameraDirectory(archiveRoot, cameraId).resolve(String.format(Locale.ROOT, "f_%07d", frameId));
    }

    public static long parseFrameId(Path frameDir) {
        Matcher matcher = FRAME_DIR.matcher(frameDir.getFileName().toString());
        if (!matcher.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static String sanitizeArtifactName(String artifactName) {
        String normalized = artifactName.trim();
        return switch (normalized) {
            case "frame.jpg", "heatmap.u8", "result.json" -> normalized;
            default -> throw new IllegalArgumentException("unsupported artifact: " + artifactName);
        };
    }

    public static String frameArtifactHttpPath(int cameraId, long frameId, String artifactName) {
        return "/api/frame-archive/cameras/" + cameraId + "/frames/" + frameId + "/" + sanitizeArtifactName(artifactName);
    }

    public static void deleteFrameDirectory(Path frameDir) {
        try {
            Files.deleteIfExists(frameDir.resolve("frame.jpg"));
            Files.deleteIfExists(frameDir.resolve("heatmap.u8"));
            Files.deleteIfExists(frameDir.resolve("result.json"));
            Files.deleteIfExists(frameDir);
        } catch (IOException ignored) {
        }
    }
}
